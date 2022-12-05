package io.onedev.plugin.maven;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.apache.tools.ant.taskdefs.Chmod;
import org.apache.tools.ant.taskdefs.Copy;
import org.apache.tools.ant.taskdefs.Expand;
import org.apache.tools.ant.taskdefs.Zip;
import org.apache.tools.ant.types.FileSet;
import org.apache.tools.ant.types.FilterSet;
import org.apache.tools.ant.types.ZipFileSet;
import org.codehaus.plexus.archiver.Archiver;
import org.codehaus.plexus.archiver.manager.ArchiverManager;
import org.codehaus.plexus.archiver.manager.NoSuchArchiverException;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;

import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.io.Files;

/**
 * Package Artifacts Mojo.
 */
@Mojo(name = "package-artifacts", requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class PackageArtifactsMojo extends AbstractMojo {
	
	@Parameter(required = true, readonly = true, defaultValue = "${project}")
	private MavenProject project;
	
	@Component
    private ArchiverManager archiverManager;    

    /**
     * The entry point to Aether, i.e. the component doing all the work.
     */
	@Component
    private RepositorySystem repoSystem;

    /**
     * The current repository/network configuration of Maven.
     */
	@Parameter(readonly = true, defaultValue = "${repositorySystemSession}")
    private RepositorySystemSession repoSession;

    /**
     * The project's remote repositories to use for the resolution of project dependencies.
     */
	@Parameter(readonly = true, required = true, defaultValue = "${project.remoteProjectRepositories}")
    private List<RemoteRepository> remoteRepos; 	

    private void copyBatchCommand(org.apache.tools.ant.Project antProject, String commandName, String commandDisplayName) {
    	File sandboxDir = new File(project.getBuild().getDirectory(), PluginConstants.SANDBOX);
		Copy copy = new Copy();
		copy.setProject(antProject);
		copy.setTofile(new File(sandboxDir, "bin/" + commandName + ".bat"));
		copy.setFile(new File(project.getBasedir(), "jsw/AppCommand.bat.in"));
		FilterSet filterSet = copy.createFilterSet();
		filterSet.addFilter("set_fixed_command", "set _FIXED_COMMAND=console");
		filterSet.addFilter("set_pass_through", "set _PASS_THROUGH=true");
		filterSet.addFilter("passthrough_parameters", "");
		String propsAndParams = String.format("wrapper.logfile.loglevel=NONE wrapper.console.title=\"OneDev %s\" wrapper.name=onedev_%s wrapper.displayname=\"OneDev %s\" wrapper.description=\"OneDev %s\" -- %s", 
				commandDisplayName, commandName, commandDisplayName, commandDisplayName, commandName);
		filterSet.addFilter("properties_and_parameters", propsAndParams);
		copy.execute();
    }
    
    private void copyShellCommand(org.apache.tools.ant.Project antProject, String commandName, String commandDisplayName) {
    	File sandboxDir = new File(project.getBuild().getDirectory(), PluginConstants.SANDBOX);
		Copy copy = new Copy();
		copy.setProject(antProject);
		copy.setTofile(new File(sandboxDir, "bin/" + commandName + ".sh"));
		copy.setFile(new File(project.getBasedir(), "jsw/App.sh.in"));
		FilterSet filterSet = copy.createFilterSet();
		filterSet.addFilter("app.name", "onedev_" + commandName);
		filterSet.addFilter("app.long.name", "OneDev " + commandDisplayName);
		filterSet.addFilter("app.description", "OneDev " + commandDisplayName);
		filterSet.addFilter("set_fixed_command", "FIXED_COMMAND=console");
		filterSet.addFilter("set_pass_through", "PASS_THROUGH=true");
		
		String propsAndParams = String.format("wrapper.logfile.loglevel=NONE wrapper.console.title='OneDev %s' wrapper.description='OneDev %s' -- %s", 
				commandDisplayName, commandName, commandName);
		filterSet.addFilter("properties_and_parameters", propsAndParams);
		copy.execute();
    }
    
    @Override
    public void execute() throws MojoExecutionException {
    	if ("jar".equals(project.getPackaging())) {
			PluginUtils.checkResolvedArtifacts(project, true);
	
			File buildDir = new File(project.getBuild().getDirectory());
			File outputDir = new File(project.getBuild().getOutputDirectory());
	
			File productPropsFile = new File(outputDir, PluginConstants.PRODUCT_PROPERTY_FILE);
			
			File jarFile = new File(buildDir, project.getBuild().getFinalName() + ".jar");
	
			File sandboxDir = new File(buildDir, PluginConstants.SANDBOX);
	
			JarOutputStream jos = null;
			try {
				Manifest manifest = new Manifest();
				manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
				jos = new JarOutputStream(new FileOutputStream(jarFile), manifest);
				jos.setLevel(9);
	
		    	for (String path: PluginUtils.listFiles(outputDir, null, null)) {
		    		File file = new File(outputDir, path);
					PluginUtils.addFileToJar(jos, file, path);
		    	}
	
				// include part of sandbox in product jar in order to generate run environment when develop standalone plugins
		    	if (productPropsFile.exists()) { 
			    	for (String path: PluginUtils.listFiles(sandboxDir, null, 
			    			new String[]{"site/lib/*.jar", "boot/system.classpath"})) {
			    		File file = new File(sandboxDir, path);
						PluginUtils.addFileToJar(jos, file, PluginConstants.SANDBOX + "/" + path);
			    	}
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			} finally {
				IOUtil.close(jos);
			}
			
			project.getArtifact().setFile(jarFile);
			
			if (sandboxDir.exists()) {
				org.apache.tools.ant.Project antProject = PluginUtils.newAntProject(getLog());
				PluginUtils.populateArtifacts(project, sandboxDir, repoSystem, repoSession, remoteRepos);
	
				if (productPropsFile.exists()) {
					File jswDir = new File(project.getBasedir(), "jsw");
					File deltaPackFile = null;
					for (File file: jswDir.listFiles()) {
						if (file.getName().startsWith("wrapper-delta-pack-") && file.getName().endsWith(".zip")) {
							deltaPackFile = file;
							break;
						}
					}
					if (deltaPackFile == null)
						throw new RuntimeException("Unable to find file " + jswDir.getAbsolutePath() + "/wrapper-delta-pack-<version>-st.zip");
					
					Expand expand = new Expand();
					expand.setProject(antProject);
					
					expand.setSrc(deltaPackFile);
					File jswExtractDir = new File(buildDir, "jsw");
					expand.setDest(jswExtractDir);
					expand.execute();
					
					File wrapperDir = null;
					for (File file: jswExtractDir.listFiles()) {
						if (file.getName().startsWith("wrapper-delta-pack-")) {
							wrapperDir = file;
							break;
						}
					}
					if (wrapperDir == null)
						throw new RuntimeException("Unable to find wrapper delta pack directory under " + jswExtractDir.getAbsolutePath());
					
					File bootDir = new File(sandboxDir, "boot");
					Copy copy = new Copy();
					copy.setProject(antProject);
					copy.setTodir(bootDir);
					
					FileSet fileSet = new FileSet();
					fileSet.setDir(new File(wrapperDir, "bin"));
					fileSet.setIncludes("wrapper-*");
					copy.addFileset(fileSet);
					
					fileSet = new FileSet();
					fileSet.setDir(new File(wrapperDir, "lib"));
					fileSet.setIncludes("libwrapper-*, *.dll, wrapper.jar");
					copy.addFileset(fileSet);
					
					copy.execute();
	
					File binDir = new File(sandboxDir, "bin");
					
					copy = new Copy();
					copy.setProject(antProject);
					copy.setTofile(new File(binDir, "server.bat"));
					copy.setFile(new File(jswDir, "AppCommand.bat.in"));
					FilterSet filterSet = copy.createFilterSet();
					filterSet.addFilter("set_fixed_command", "");
					filterSet.addFilter("set_pass_through", "");
					filterSet.addFilter("properties_and_parameters", "--");
					filterSet.addFilter("passthrough_parameters", "");
					copy.execute();
	
					copyBatchCommand(antProject, "restore-db", "Restore Database");
					copyBatchCommand(antProject, "backup-db", "Backup Database");
					copyBatchCommand(antProject, "upgrade", "Upgrade");
					copyBatchCommand(antProject, "apply-db-constraints", "Apply DB Constraints");
					copyBatchCommand(antProject, "reset-admin-password", "Reset Admin Password");
					
					FilterSet serverFilterSet = new FilterSet();
					serverFilterSet.addFilter("classpath2", "");
					serverFilterSet.addFilter("maxmemory.value", "#wrapper.java.maxmemory=1024");
					serverFilterSet.addFilter("maxmemory.percent", "wrapper.java.maxmemory.percent=50");
					serverFilterSet.addFilter("bootstrap.class", "io.onedev.commons.bootstrap.Bootstrap");
					serverFilterSet.addFilter("app.name", "onedev");
					serverFilterSet.addFilter("app.long.name", "OneDev");
					serverFilterSet.addFilter("app.description", "OneDev");
					
					copy = new Copy();
					copy.setTofile(new File(binDir, "server.sh"));
					copy.setFile(new File(jswDir, "App.sh.in"));
					filterSet = copy.createFilterSet();
					filterSet.addConfiguredFilterSet(serverFilterSet);
					filterSet.addFilter("set_fixed_command", "");
					filterSet.addFilter("set_pass_through", "");
					filterSet.addFilter("properties_and_parameters", "--");
					copy.execute();
					
					copyShellCommand(antProject, "restore-db", "Restore Database");
					copyShellCommand(antProject, "backup-db", "Backup Database");
					copyShellCommand(antProject, "upgrade", "Upgrade");
					copyShellCommand(antProject, "apply-db-constraints", "Apply DB Constraints");
					copyShellCommand(antProject, "reset-admin-password", "Reset Admin Password");
	
					copy = new Copy();
					copy.setProject(antProject);
					copy.setTofile(new File(sandboxDir, "conf/wrapper.conf"));
					copy.setFile(new File(jswDir, "wrapper.conf"));
					copy.createFilterSet().addConfiguredFilterSet(serverFilterSet);
					copy.execute();
					
					copy = new Copy();
					copy.setProject(antProject);
					copy.setTofile(new File(sandboxDir, "conf/wrapper-license.conf"));
					copy.setFile(new File(jswDir, "server-license.conf"));
					copy.execute();
					
					FilterSet agentFilterSet = new FilterSet();
					agentFilterSet.addFilter("classpath2", "wrapper.java.classpath.2=../lib/agentVersion/*.jar");
					agentFilterSet.addFilter("maxmemory.value", "wrapper.java.maxmemory=1024");
					agentFilterSet.addFilter("maxmemory.percent", "");
					agentFilterSet.addFilter("bootstrap.class", "io.onedev.agent.Agent");
					agentFilterSet.addFilter("app.name", "onedevagent");
					agentFilterSet.addFilter("app.long.name", "OneDev Agent");
					agentFilterSet.addFilter("app.description", "OneDev Agent");
					
					File agentDir = new File(sandboxDir, "agent");
					
					copy = new Copy();
					copy.setProject(antProject);
					copy.setTofile(new File(agentDir, "bin/agent.bat"));
					copy.setFile(new File(jswDir, "AppCommand.bat.in"));
					filterSet = copy.createFilterSet();
					filterSet.addFilter("set_fixed_command", "");
					filterSet.addFilter("set_pass_through", "set _PASS_THROUGH=app_args");
					filterSet.addFilter("properties_and_parameters", "");
					filterSet.addFilter("passthrough_parameters", "set _PARAMETERS=--");
					copy.execute();
					
					copy = new Copy();
					copy.setTofile(new File(agentDir, "bin/agent.sh"));
					copy.setFile(new File(jswDir, "App.sh.in"));
					filterSet = copy.createFilterSet();
					filterSet.addConfiguredFilterSet(agentFilterSet);
					filterSet.addFilter("set_fixed_command", "");
					filterSet.addFilter("set_pass_through", "");
					filterSet.addFilter("properties_and_parameters", "--");
					copy.execute();
					
					copy = new Copy();
					copy.setProject(antProject);
					copy.setTofile(new File(agentDir, "conf/wrapper.conf"));
					copy.setFile(new File(jswDir, "wrapper.conf"));
					copy.createFilterSet().addConfiguredFilterSet(agentFilterSet);
					copy.execute();
					
					copy = new Copy();
					copy.setProject(antProject);
					copy.setTofile(new File(agentDir, "conf/wrapper-license.conf"));
					copy.setFile(new File(jswDir, "agent-license.conf"));
					copy.execute();
										
					Chmod chmod = new Chmod();
					chmod.setProject(antProject);
					chmod.setDir(sandboxDir);
					chmod.setPerm("755");
		    		Properties productProps = PluginUtils.loadProperties(productPropsFile);
					String executables = productProps.getProperty("executables");
					chmod.setIncludes(executables);
					chmod.execute();
					
					String prefix = "onedev-" + project.getVersion();
					Zip zip = new Zip();
					zip.setProject(antProject);
					
					zip.setDestFile(new File(project.getBuild().getDirectory(), prefix + ".zip"));
					
					ZipFileSet zipFileSet = new ZipFileSet();
					zipFileSet.setDir(sandboxDir);
					zipFileSet.setPrefix(prefix);
					zipFileSet.setExcludes(executables + ", boot/system.classpath, site/lib/*.jar");
					zip.addZipfileset(zipFileSet);
					
					zipFileSet = new ZipFileSet();
					zipFileSet.setDir(sandboxDir);
					zipFileSet.setPrefix(prefix);
					zipFileSet.setIncludes(executables);
					zipFileSet.setFileMode("755");
					zip.addZipfileset(zipFileSet);
					
					zip.execute();
					
					try {
						FileUtils.copyDirectoryStructure(agentDir, new File(buildDir, "agent"));
					} catch (IOException e) {
						throw new RuntimeException(e);
					}

					agentDir = new File(buildDir, "agent");
					
					File agentBootDir = new File(agentDir, "boot");
					for (File file: bootDir.listFiles()) {
						if (file.getName().startsWith("libwrapper-") 
								|| file.getName().startsWith("wrapper-") 
								|| file.getName().equals("wrapper.jar")) {
							try {
								FileUtils.copyFileToDirectory(file, agentBootDir);
							} catch (IOException e) {
								throw new RuntimeException(e);
							}
						}
					}

					Artifact agentArtifact = null;
	    	    	for (Artifact artifact: project.getArtifacts()) {
	    	    		if (PluginUtils.isRuntimeArtifact(artifact) && artifact.getGroupId().equals("io.onedev") && 
	    	    				artifact.getArtifactId().equals("agent")) {
	    	    			agentArtifact = artifact;
	    	    			break;
	    	    		}
	    	    	}
	    	    	if (agentArtifact == null)
	    	    		throw new RuntimeException("Unable to find agent artifact");

    	    		Properties agentProps = Preconditions.checkNotNull(PluginUtils.readProperties(
    	    				agentArtifact.getFile(), PluginConstants.AGENT_PROPERTY_FILE));
	    	    	
    	    		Set<String> agentDependencies = new HashSet<>();
    	    		for (String dependency: Splitter.on(";").omitEmptyStrings().split(agentProps.getProperty("dependencies"))) {
    	    			int index = dependency.indexOf(':');
    	    			agentDependencies.add(dependency.substring(0, index));
    	    		}
    	    		
					File agentLibDir = new File(agentDir, "lib");
					try {
						FileUtils.copyFileToDirectory(agentArtifact.getFile(), new File(agentLibDir, agentArtifact.getVersion()));
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
        	    	for (Artifact artifact: project.getArtifacts()) {
        	    		if (PluginUtils.isRuntimeArtifact(artifact)) {
            	    		if (agentDependencies.contains(artifact.getGroupId() + "." + artifact.getArtifactId())) { 
    							try {
									FileUtils.copyFileToDirectory(artifact.getFile(), new File(agentLibDir, agentArtifact.getVersion()));
								} catch (IOException e) {
									throw new RuntimeException(e);
								}
            	    		}
        	    		}
        	    	}    	    

        	    	try {
						File agentConfFile = new File(agentDir, "conf/wrapper.conf");
						String wrapperConfContent = FileUtils.fileRead(agentConfFile);
						wrapperConfContent = wrapperConfContent.replace("agentVersion", agentArtifact.getVersion());
						FileUtils.fileWrite(agentConfFile, wrapperConfContent);
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
	    	    	
        	    	try {
            	    	byte[] logbackConfBytes = Preconditions.checkNotNull(
            	    			PluginUtils.readBytes(agentArtifact.getFile(), "agent/conf/logback.xml"));
						Files.write(logbackConfBytes, new File(agentDir, "conf/logback.xml"));
						byte[] agentPropsBytes = Preconditions.checkNotNull(
            	    			PluginUtils.readBytes(agentArtifact.getFile(), "agent/conf/agent.properties"));
						Files.write(agentPropsBytes, new File(agentDir, "conf/agent.properties"));
						Files.touch(new File(agentDir, "conf/attributes.properties"));
						new File(agentDir, "logs").mkdir();
						Files.touch(new File(agentDir, "logs/console.log"));						
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
        	    	
					chmod = new Chmod();
					chmod.setProject(antProject);
					chmod.setDir(agentDir);
					chmod.setPerm("755");
					chmod.setIncludes(executables);
					chmod.execute();
	    	    } else {
					Archiver archiver;
					try {
						archiver = archiverManager.getArchiver("zip");
					} catch (NoSuchArchiverException e) {
						throw new RuntimeException(e);
					}
			    	
					Artifact productArtifact = null;
	    	    	for (Artifact artifact: project.getArtifacts()) {
	    	    		if (PluginUtils.isRuntimeArtifact(artifact) 
	    	    				&& PluginUtils.containsFile(artifact.getFile(), PluginConstants.PRODUCT_PROPERTY_FILE)) {
	    	    			productArtifact = artifact;
	    	    			break;
	    	    		}
	    	    	}

	    	    	if (productArtifact == null)
	    	    		throw new RuntimeException("Illegal state encountered, please run a clean build");
	    	    	
			    	String productFQN = productArtifact.getGroupId() + ":" + productArtifact.getArtifactId() + ":" 
									+ productArtifact.getType() + ":" + productArtifact.getVersion();
	
					Collection<Artifact> bootstrapArtifacts = PluginUtils.getBootstrapArtifacts(
							project, repoSystem, repoSession, remoteRepos);

			    	for (Artifact artifact: project.getArtifacts()) {
			    		if (PluginUtils.isRuntimeArtifact(artifact) && !bootstrapArtifacts.contains(artifact) 
			    				&& !artifact.getDependencyTrail().contains(productFQN)) {
				    		archiver.addFile(artifact.getFile(), PluginUtils.getArtifactKey(artifact));
			    		}
			    	}
			    	
			    	archiver.addFile(project.getArtifact().getFile(), PluginUtils.getArtifactKey(project.getArtifact()));
	
					File distFile = new File(buildDir, project.getBuild().getFinalName() + ".zip");
			    	archiver.setDestFile(distFile);
			    	
			    	try {
						archiver.createArchive();
					} catch (Exception e) {
						throw PluginUtils.unchecked(e);
					}		
				}							
			}
    	}
    }    
}
