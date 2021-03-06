/*
The MIT License (MIT)

Copyright (c) 2013 Michael Rice <me@michaelrice.com>

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in
all copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
THE SOFTWARE.
 */
package org.complykit.licensecheck.mojo;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import org.apache.maven.RepositoryUtils;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.complykit.licensecheck.model.LicenseDescriptor;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;

/**
 * This plugin uses Aether to cruise through the dependencies in the project, find the related pom files,
 * extract the license information, and then convert the licenses into codes.
 * If a license cannot be verified or if the license appears on the user's blacklist, then the build will fail.
 *
 * For more, visit https://github.com/mrice/license-check
 *
 * @author michael.rice
 */
@Mojo(name = "os-check")
public class OpenSourceLicenseCheckMojo extends AbstractMojo
{

  private static final Locale LOCALE = Locale.ENGLISH;

  private enum CheckOutcome
  {
    LICENSE_INVALID_NO_INFO( 0, "INVALID (no license info)" ),
    ARTIFACT_EXCLUDED( 1, "ARTIFACT_EXCLUDED" ),
    LICENSE_INVALID_BLACKLISTED( 2, "INVALID (blacklisted)" ),
    LICENSE_INVALID_NOT_RECOGNIZED( 3, "INVALID (not recognized)" ),
    LICENSE_VALID( 4, "VALID" );

    public final int displayOrder;
    public final String displayName;

    CheckOutcome(int displayOrder, String displayName)
    {
      this.displayOrder = displayOrder;
      this.displayName = displayName;
    }
  }

  private static final class CheckResult implements Comparable<CheckResult>
  {
    public final CheckOutcome outcome;
    public final Artifact artifact;
    public final String licenseCode;

    private CheckResult(Artifact artifact, CheckOutcome status) {
      this(artifact,null,status);
    }

    private CheckResult(Artifact artifact,String licenseCode, CheckOutcome status)
    {
      this.artifact = artifact;
      this.licenseCode = licenseCode;
      this.outcome = status;
    }

    public boolean hasStatus(CheckOutcome status) {
      return this.outcome == status;
    }

    public boolean hasUnknownLicense() {
      return hasStatus( CheckOutcome.LICENSE_INVALID_NO_INFO );
    }

    @Override
    public int compareTo(CheckResult o)
    {
      if ( hasUnknownLicense() == o.hasUnknownLicense() ) {
        return 0;
      }
      if ( hasUnknownLicense() ) {
        return 1;
      }
      return -1;
    }
  }

  /**
   * This is the repo system required by Aether
   *
   * For more, visit http://blog.sonatype.com/people/2011/01/how-to-use-aether-in-maven-plugins/
   */
  @Component
  RepositorySystem repoSystem;

  @Component
  final MavenProject project = null;

  /**
   * The current repository and network configuration of Maven
   *
   * For more, visit http://blog.sonatype.com/people/2011/01/how-to-use-aether-in-maven-plugins/
   *
   * @readonly
   */
  @Parameter(defaultValue = "${repositorySystemSession}")
  RepositorySystemSession repoSession;

  /**
   * This is the project's remote repositories that can be used for resolving plugins and their dependencies
   */
  @Parameter(defaultValue = "${project.remotePluginRepositories}")
  List<RemoteRepository> remoteRepos;

  /**
   * This is the maximum number of parents to search through (in case there's a malformed pom).
   */
  @Parameter(property = "os-check.recursion-limit", defaultValue = "12")
  int maxSearchDepth;

  /**
   * A list of artifacts that should be excluded from consideration. Example: &lt;configuration&gt; &lt;excludes&gt;
   * &lt;param&gt;full:artifact:coords&lt;/param&gt;>
   * &lt;/excludes&gt; &lt;/configuration&gt;
   */
  @Parameter(property = "os-check.excludes")
  String[] excludes;

  /**
   * A list of artifacts that should be excluded from consideration. Example: &lt;configuration&gt;
   * &lt;excludesRegex&gt; &lt;param&gt;full:artifact:coords&lt;/param&gt;>
   * &lt;/excludesRegex&gt; &lt;/configuration&gt;
   */
  @Parameter(property = "os-check.excludesRegex")
  String[] excludesRegex;

  @Parameter(property = "os-check.excludesNoLicense")
  boolean excludeNoLicense;

  /**
   * A list of blacklisted licenses. Example: &lt;configuration&gt; &lt;blacklist&gt;
   * &lt;param&gt;agpl-3.0&lt;/param&gt; &lt;param&gt;gpl-2.0&lt;/param&gt;
   * &lt;param&gt;gpl-3.0&lt;/param&gt; &lt;/blacklist&gt; &lt;/configuration&gt;
   */
  @Parameter(property = "os-check.blacklist")
  String[] blacklist;

  /**
   * A list of whitelisted licenses. Example: &lt;configuration&gt; &lt;whitelist&gt;
   * &lt;param&gt;agpl-3.0&lt;/param&gt; &lt;param&gt;gpl-2.0&lt;/param&gt;
   * &lt;param&gt;gpl-3.0&lt;/param&gt; &lt;/blacklist&gt; &lt;/configuration&gt;
   */
  @Parameter(property = "os-check.whitelist")
  String[] whitelist;

  /**
   * A list of scopes to exclude. May be used to exclude artifacts with test or provided scope from license check.
   * Example: &lt;configuration&gt; &lt;excludedScopes&gt; &lt;param&gt;test&lt;/param&gt;
   * &lt;param&gt;provided&lt;/param&gt; &lt;/excludedScopes&gt; &lt;/configuration&gt;
   */
  @Parameter(property = "os-check.excludedScopes")
  String[] excludedScopes;

  /**
   * Used to hold the list of license descriptors. Generation is lazy on the first method call to use it.
   */
  List<LicenseDescriptor> descriptors = null;

  public void execute() throws MojoExecutionException, MojoFailureException
  {

    getLog().info("------------------------------------------------------------------------");
    getLog().info("VALIDATING OPEN SOURCE LICENSES                                         ");
    getLog().info("------------------------------------------------------------------------");

    final Set<String> excludeSet = getAsLowerCaseSet(excludes);
    final Set<String> blacklistSet = getAsLowerCaseSet(blacklist);
    final Set<String> whitelistSet = getAsLowerCaseSet(whitelist);
    final Set<String> excludedScopesSet = getAsLowerCaseSet(excludedScopes);
    final List<Pattern> excludePatternList = getAsPatternList(excludesRegex);

    final Set<Artifact> artifacts = project.getDependencyArtifacts();
    getLog().info("Validating licenses for " + artifacts.size() + " artifact(s)");

    final Map<String, CheckResult> licenses = new HashMap<String, CheckResult>();

    boolean buildFails = false;
    for (final Artifact artifact : artifacts)
    {
      if (!artifactIsOnExcludeList(excludeSet, excludePatternList, excludedScopesSet, artifact))
      {
        final ArtifactRequest request = new ArtifactRequest();
        request.setArtifact(RepositoryUtils.toArtifact(artifact));
        request.setRepositories(remoteRepos);

        ArtifactResult result = null;
        try {
          result = repoSystem.resolveArtifact(repoSession, request);
          getLog().info(result.toString());
        }
        catch (final ArtifactResolutionException e)
        {
            throw new MojoExecutionException( e.getMessage(), e );
        }

        String licenseName = "";
        final CheckOutcome outcome;
        try {
          licenseName = recurseForLicenseName(RepositoryUtils.toArtifact(result.getArtifact()), 0);
        } catch (IOException e) {
          getLog().error("Error reading license information", e);
        }
        String licenseCode = convertLicenseNameToCode(licenseName);
        if (licenseCode == null) {
          outcome = CheckOutcome.LICENSE_INVALID_NO_INFO;
          if (! excludeNoLicense) {
            buildFails = true;
            getLog().warn("Build will fail because of artifact '" + toCoordinates(artifact) + "' and license'" + licenseName + "'.");
          }
        } else if ( !blacklistSet.isEmpty() && isContained(blacklistSet, licenseCode)) {
          outcome = CheckOutcome.LICENSE_INVALID_BLACKLISTED;
          buildFails = true;
          licenseCode += " IS ON YOUR BLACKLIST";
        } else if ( !whitelistSet.isEmpty() && ! isContained(whitelistSet, licenseCode) ) {
          outcome = CheckOutcome.LICENSE_INVALID_NOT_RECOGNIZED;
          buildFails = true;
          licenseCode += " IS NOT ON YOUR WHITELIST";
        } else {
          outcome = CheckOutcome.LICENSE_VALID;
        }
        licenses.put(toCoordinates(artifact), new CheckResult(artifact,licenseCode,outcome));
      } else {
        licenses.put(toCoordinates(artifact), new CheckResult(artifact, CheckOutcome.ARTIFACT_EXCLUDED) );
      }
    }

    getLog().info("");
    getLog().info("This plugin validates that the artifacts you're using have a");
    getLog().info("license declared in the pom. It then tries to determine whether ");
    getLog().info("the license is one of the Open Source Initiative (OSI) approved ");
    getLog().info("licenses. If it can't find a match or if the license is on your ");
    getLog().info("declared blacklist or not on your declared whitelist, then the build will fail.");
    getLog().info("");
    getLog().info("This plugin and its author are not associated with the OSI.");
    getLog().info("Please send me feedback: me@michaelrice.com. Thanks!");
    getLog().info("");

    getLog().info("--[ Licenses found ]------ ");

    final List<CheckResult> sorted = new ArrayList<>( licenses.values() );
    Collections.sort( sorted , new Comparator<CheckResult>()
    {
      @Override
      public int compare(CheckResult o1, CheckResult o2)
      {
        return Integer.compare(o1.outcome.displayOrder,o2.outcome.displayOrder);
      }
    } );

    for (final CheckResult result : sorted)
    {
      final String lic = result.licenseCode == null ? "n/a" : result.licenseCode;
      final String licPart = " [ " + leftPad(lic,10 )+ " ]";
      final String statusPart = rightPad(result.outcome.displayName, 25 );
      final String combined = statusPart + " "+licPart;
      getLog().info( "LICENSE: " + combined +" "+result.artifact );
    }

    if (buildFails) {
      getLog().info("");
      getLog().info("RESULT: At least one license could not be verified or appears on your blacklist or is not on your whitelist. Build fails.");
      getLog().info("");
      throw new MojoFailureException("blacklist/whitelist of unverifiable license");
    }
    getLog().info("");
    getLog().info("RESULT: license check complete, no issues found.");
    getLog().info("");
  }

  private static String rightPad(String input,int len)
  {
    while ( input.length() < len ) {
      input += " ";
    }
    return input;
  }

  private static String leftPad(String input,int len)
  {
    while ( input.length() < len ) {
      input = " "+input;
    }
    return input;
  }

  Set<String> getAsLowerCaseSet(final String[] src)
  {
    final Set<String> target = new HashSet<String>();
    if (src != null) {
      for (final String s : src) {
        target.add(s.toLowerCase(LOCALE));
      }
    }
    return target;
  }

  List<Pattern> getAsPatternList(final String[] src)
  {
    final List<Pattern> target = new ArrayList<Pattern>();
    if (src != null) {
      for (final String s : src) {
        try {
          final Pattern pattern = Pattern.compile(s);
          target.add(pattern);
        } catch (final PatternSyntaxException e) {
          getLog().warn("The regex " + s + " is invalid: " + e.getLocalizedMessage());
        }

      }
    }
    return target;
  }

  String toCoordinates(Artifact artifact)
  {
    return artifact.getGroupId() + ":" + artifact.getArtifactId() + ":" + artifact.getVersion();
  }

  String recurseForLicenseName(final Artifact artifact, final int currentDepth) throws IOException
  {

    final File artifactDirectory = artifact.getFile().getParentFile();
    String directoryPath = artifactDirectory.getAbsolutePath();
    directoryPath += "/" + artifact.getArtifactId() + "-" + artifact.getVersion() + ".pom";

    final String pom = readPomContents(directoryPath);

    // first, look for a license
    String licenseName = extractLicenseName(pom);
    if (licenseName == null) {
      final String parentArtifactCoords = extractParentCoords(pom);
      if (parentArtifactCoords != null) {
        // search for the artifact
        final Artifact parent = retrieveArtifact(parentArtifactCoords);
        if (parent != null) {
          // check the recursion depth
          if (currentDepth >= maxSearchDepth) {
            return null; // TODO throw an exception
          }
          licenseName = recurseForLicenseName(parent, currentDepth + 1);
        } else {
          return null;
        }
      } else {
        return null;
      }
    }
    return licenseName;
  }

  String readPomContents(final String path) throws IOException
  {

    final StringBuffer buffer = new StringBuffer();
    BufferedReader reader = null;

    reader = new BufferedReader(new FileReader(path));

    String line = null;

    while ((line = reader.readLine()) != null) {
      buffer.append(line);
    }


    reader.close();


    return buffer.toString();
  }

  /**
   * This function looks for the license textual description. I didn't really want to parse the pom xml since we're
   * just looking for one little snippet of the content.
   *
   * @param raw
   * @return
   */
  // TODO make this more elegant and less brittle
  String extractLicenseName(final String raw)
  {
    final String licenseTagStart = "<license>", licenseTagStop = "</license>";
    final String nameTagStart = "<name>", nameTagStop = "</name>";
    if (raw.indexOf(licenseTagStart) != -1) {
      final String licenseContents = raw.substring(raw.indexOf(licenseTagStart) + licenseTagStart.length(), raw.indexOf(licenseTagStop));
      final String name = licenseContents.substring(licenseContents.indexOf(nameTagStart) + nameTagStart.length(), licenseContents.indexOf(nameTagStop));
      return name;
    }
    return null;
  }

  /**
   * @param raw
   * @return
   */
  // TODO obviously this code needs a lot of error protection and handling
  String extractParentCoords(final String raw)
  {
    final String parentTagStart = "<parent>", parentTagStop = "</parent>";
    final String groupTagStart = "<groupId>", groupTagStop = "</groupId>";
    final String artifactTagStart = "<artifactId>", artifactTagStop = "</artifactId>";
    final String versionTagStart = "<version>", versionTagStop = "</version>";

    if (raw.indexOf(parentTagStart) == -1) {
      return null;
    }
    final String contents = raw.substring(raw.indexOf(parentTagStart) + parentTagStart.length(), raw.indexOf(parentTagStop));
    final String group = contents.substring(contents.indexOf(groupTagStart) + groupTagStart.length(), contents.indexOf(groupTagStop));
    final String artifact = contents.substring(contents.indexOf(artifactTagStart) + artifactTagStart.length(), contents.indexOf(artifactTagStop));
    final String version = contents.substring(contents.indexOf(versionTagStart) + versionTagStart.length(), contents.indexOf(versionTagStop));
    return group + ":" + artifact + ":" + version;
  }

  /**
   * Uses Aether to retrieve an artifact from the repository.
   *
   * @param coordinates as in groupId:artifactId:version
   * @return the located artifact
   */
  Artifact retrieveArtifact(final String coordinates)
  {

    final ArtifactRequest request = new ArtifactRequest();
    request.setArtifact(new DefaultArtifact(coordinates));
    request.setRepositories(remoteRepos);

    ArtifactResult result = null;
    try {
      result = repoSystem.resolveArtifact(repoSession, request);
    } catch (final ArtifactResolutionException e) {
      getLog().error("Could not resolve parent artifact (" + coordinates + "): " + e.getMessage());
    }

    if (result != null) {
      return RepositoryUtils.toArtifact(result.getArtifact());
    }
    return null;
  }

  /**
   * This is the method that looks at the textual description of the license and returns a code version by running
   * regex. It needs a lot of optimization.
   *
   * @param licenseName
   * @return
   */
  String convertLicenseNameToCode(final String licenseName)
  {
    if (licenseName == null) {
      return null;
    }
    if (descriptors == null) {
      loadDescriptors();
    }
    for (final LicenseDescriptor descriptor : descriptors) {
      // TODO there's gotta be a faster way to do this
      final Pattern pattern = Pattern.compile(descriptor.getRegex(), Pattern.CASE_INSENSITIVE);
      final Matcher matcher = pattern.matcher(licenseName);
      if (matcher.find()) {
        return descriptor.getCode();
      }
    }
    return null;
  }

  /**
   * This method looks for a resource file bundled with the plugin that contains a tab delimited version of the regex
   * and the codes. Future versions of the plugin may retrieve fresh versions of the file from the server so we can
   * take advantage of improvements in what we know and what we learn.
   */
  // TODO I know, I know... this is really raw... will make it prettier
  void loadDescriptors()
  {

    final String licensesPath = "/licenses.txt";
    final InputStream is = getClass().getResourceAsStream(licensesPath);
    BufferedReader reader = null;
    descriptors = new ArrayList<LicenseDescriptor>();
    final StringBuffer buffer = new StringBuffer();
    try {
      reader = new BufferedReader(new InputStreamReader(is));
      String line = null;
      while ((line = reader.readLine()) != null) {
        buffer.append(line + "\n");
      }
    } catch (final Exception e) {
      getLog().error(e);
    } finally {
      try {
        reader.close();
      } catch (final IOException e) {
        // TODO
        e.printStackTrace();
      }
    }

    final String lines[] = buffer.toString().split("\n");
    for (final String line : lines) {
      final String columns[] = line.split("\\t");
      final LicenseDescriptor descriptor = new LicenseDescriptor();
      descriptor.setCode(columns[0]);
      descriptor.setLicenseName(columns[2]);
      descriptor.setRegex(columns[3]);
      descriptors.add(descriptor);
    }
  }

  /**
   * Checks to see if an artifact is on the user's exclude list
   *
   * @param excludeSet
   * @param patternList
   * @param excludedScopes
   * @param artifact
   * @return
   */
  boolean artifactIsOnExcludeList(final Set<String> excludeSet, final List<Pattern> patternList,
      final Set<String> excludedScopes, final Artifact artifact)
  {
    return isExcludedTemplate(excludeSet, patternList, toCoordinates(artifact)) || excludedScopes.contains(artifact.getScope());
  }

  boolean isExcludedTemplate(final Set<String> excludeSet, final List<Pattern> patternList,
      final String template)
  {
    if (isContained(excludeSet, template)) {
      return true;
    }
    for (final Pattern pattern : patternList) {
      if (pattern.matcher(template).matches()) {
        return true;
      }
    }
    return false;
  }

  boolean isContained(final Set<String> set, final String template)
  {
    if (set != null && template != null) {
      return set.contains(template.toLowerCase(LOCALE));
    }
    return false;
  }

}
