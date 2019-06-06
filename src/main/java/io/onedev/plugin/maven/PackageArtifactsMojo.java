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
import org.codehaus.plexus.util.IOUtil;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;

/**
 * @goal package-artifacts
 * @requiresDependencyResolution compile+runtime
 */
public class PackageArtifactsMojo extends AbstractMojo {
	
	/**
     * @parameter default-value="${project}"
     * @required
     * @readonly
	 */
	private MavenProject project;
	
    /**
     * @component
     */
    private ArchiverManager archiverManager;    

    /**
     * The entry point to Aether, i.e. the component doing all the work.
     *
     * @component
     */
    private RepositorySystem repoSystem;

    /**
     * The current repository/network configuration of Maven.
     *
     * @parameter default-value="${repositorySystemSession}"
     * @readonly
     */
    private RepositorySystemSession repoSession;

    /**
     * The project's remote repositories to use for the resolution of project dependencies.
     *
     * @parameter default-value="${project.remoteProjectRepositories}"
     * @readonly
     */
    private List<RemoteRepository> remoteRepos; 	

    private void copyBatchCommand(org.apache.tools.ant.Project antProject, String commandName, String commandDisplayName) {
    	File sandboxDir = new File(project.getBuild().getDirectory(), PluginConstants.SANDBOX);
		Copy copy = new Copy();
		copy.setProject(antProject);
		copy.setTofile(new File(sandboxDir, "bin/" + commandName + ".bat"));
		copy.setFile(new File(project.getBasedir(), "jsw/AppCommand.bat.in"));
		FilterSet filterSet = copy.createFilterSet();
		filterSet.addFilter("set_fixed_command", "set _FIXED_COMMAND=console");
		String propsAndParams = String.format("wrapper.logfile.loglevel=NONE wrapper.console.title=\"OneDev %s\" wrapper.pidfile=onedev_%s.pid wrapper.name=onedev_%s wrapper.displayname=\"OneDev %s\" wrapper.description=\"OneDev %s\" -- %s", 
				commandDisplayName, commandName, commandName, commandDisplayName, commandDisplayName, commandName);
		filterSet.addFilter("properties_and_parameters", propsAndParams);
		copy.execute();
    }
    
    private void copyShellCommand(org.apache.tools.ant.Project antProject, String commandName, String commandDisplayName) {
    	File sandboxDir = new File(project.getBuild().getDirectory(), PluginConstants.SANDBOX);
		Copy copy = new Copy();
		copy.setProject(antProject);
		copy.setTofile(new File(sandboxDir, "bin/" + commandName + ".sh"));
		copy.setFile(new File(project.getBasedir(), "jsw/sh.script.in"));
		FilterSet filterSet = copy.createFilterSet();
		filterSet.addFilter("app.name", "onedev_" + commandName);
		filterSet.addFilter("app.long.name", "OneDev " + commandDisplayName);
		filterSet.addFilter("app.description", "OneDev " + commandDisplayName);
		filterSet.addFilter("set_fixed_command", "FIXED_COMMAND=console");
		filterSet.addFilter("set_pass_through", "PASS_THROUGH=true");
		
		String propsAndParams = String.format("wrapper.logfile.loglevel=NONE wrapper.console.title='OneDev %s' wrapper.name=onedev_%s wrapper.displayname='OneDev %s' wrapper.description='OneDev %s' -- %s", 
				commandDisplayName, commandName, commandDisplayName, commandDisplayName, commandName);
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
	
			/*
			 * Below code is adapted from Maven's DefaultArchetypeManager to create an archetype style 
			 * archive to avoid the "Can not override..." warning when use this archive to generate 
			 * an archetype.
			 */
			JarOutputStream jos = null;
			try {
				Manifest manifest = new Manifest();
				manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
				jos = new JarOutputStream(new FileOutputStream(jarFile), manifest);
				jos.setLevel(9);
	
				Collection<File> files = PluginUtils.listFiles(outputDir);
	
				for (File file : files) 
					PluginUtils.addFileToJar(jos, file, outputDir.getAbsolutePath().length());
	
				// include sandbox in product jar in order to generate sandbox when develop standalone plugins
				if (productPropsFile.exists()) { 
					files = PluginUtils.listFiles(sandboxDir);
					
					Set<String> excluded = new HashSet<String>();
					for (Artifact artifact: project.getArtifacts())
						excluded.add(PluginUtils.getArtifactKey(artifact));
					excluded.add(PluginConstants.SYSTEM_CLASSPATH);
					excluded.add(jarFile.getName());
	
					for (File file: files) {
						if (!excluded.contains(file.getName())) 
							PluginUtils.addFileToJar(jos, file, sandboxDir.getParent().length());
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
				PluginUtils.populateArtifacts(project, sandboxDir, archiverManager, repoSystem, repoSession, remoteRepos);
	
				if (productPropsFile.exists()) {
					Expand expand = new Expand();
					expand.setProject(antProject);
					expand.setSrc(new File(project.getBasedir(), "jsw/wrapper-delta-pack.zip"));
					File jswDir = new File(project.getBasedir(), "jsw");
					File wrapperDir = new File(buildDir, "wrapper");
					expand.setDest(wrapperDir);
					expand.execute();
					
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
					filterSet.addFilter("properties_and_parameters", "wrapper.pidfile=../status/onedev.pid");
					copy.execute();
	
					copyBatchCommand(antProject, "restore", "Restore");
					copyBatchCommand(antProject, "backup", "Backup");
					copyBatchCommand(antProject, "upgrade", "Upgrade");
					copyBatchCommand(antProject, "apply_db_constraints", "Apply DB Constraints");
					copyBatchCommand(antProject, "reset_admin_password", "Reset Admin Password");
					
					FilterSet appFilterSet = new FilterSet();
					appFilterSet.addFilter("app.name", "onedev");
					appFilterSet.addFilter("app.long.name", "OneDev");
					appFilterSet.addFilter("app.description", "OneDev");
					
					copy = new Copy();
					copy.setTofile(new File(binDir, "server.sh"));
					copy.setFile(new File(jswDir, "sh.script.in"));
					filterSet = copy.createFilterSet();
					filterSet.addConfiguredFilterSet(appFilterSet);
					filterSet.addFilter("set_fixed_command", "");
					filterSet.addFilter("set_pass_through", "");
					filterSet.addFilter("properties_and_parameters", "--");
					copy.execute();
					
					copyShellCommand(antProject, "restore", "Restore");
					copyShellCommand(antProject, "backup", "Backup");
					copyShellCommand(antProject, "upgrade", "Upgrade");
					copyShellCommand(antProject, "apply_db_constraints", "Apply DB Constraints");
					copyShellCommand(antProject, "reset_admin_password", "Reset Admin Password");
	
					copy = new Copy();
					copy.setProject(antProject);
					copy.setTofile(new File(sandboxDir, "conf/wrapper.conf"));
					copy.setFile(new File(jswDir, "wrapper.conf"));
					copy.createFilterSet().addConfiguredFilterSet(appFilterSet);
					copy.execute();
					
					Chmod chmod = new Chmod();
					chmod.setProject(PluginUtils.newAntProject(getLog()));
					chmod.setDir(sandboxDir);
					chmod.setPerm("755");
		    		Properties productProps = PluginUtils.loadProperties(productPropsFile);
					String executables = productProps.getProperty("executables");
					chmod.setIncludes(executables);
					chmod.execute();
					
					createAppZip("onedev", executables);
				} else {
					// Create a plugin zip which can be deployed into OneDev's site/lib directory
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
			    	
					// Create an application zip including the plugin which can run independently
					String prefix = project.getArtifactId() + "-" + project.getVersion();
		    		Properties productProps = PluginUtils.loadProperties(
		    				productArtifact.getFile(), PluginConstants.PRODUCT_PROPERTY_FILE);
					String executables = productProps.getProperty("executables");
					createAppZip(prefix, executables);
				}							
			}
    	}
    }
    
    private void createAppZip(String prefix, String executables) {
    	File sandboxDir = new File(project.getBuild().getDirectory(), PluginConstants.SANDBOX);
		Zip zip = new Zip();
		zip.setProject(PluginUtils.newAntProject(getLog()));
		
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
    }
}
