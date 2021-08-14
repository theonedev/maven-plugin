package io.onedev.plugin.maven;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.apache.tools.ant.BuildException;
import org.apache.tools.ant.taskdefs.Echo;
import org.codehaus.plexus.archiver.UnArchiver;
import org.codehaus.plexus.archiver.manager.ArchiverManager;
import org.codehaus.plexus.archiver.manager.NoSuchArchiverException;
import org.codehaus.plexus.components.io.fileselectors.IncludeExcludeFileSelector;
import org.codehaus.plexus.util.FileUtils;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;

/**
 * @goal populate-resources
 * @requiresDependencyResolution compile+runtime
 */
public class PopulateResourcesMojo extends AbstractMojo {
	
	/**
     * @parameter default-value="${project}"
     * @required
     * @readonly
	 */
	private MavenProject project;
	
	/**
	 * @parameter default-value="${bootstrap}"
	 */
	private boolean bootstrap;

	/**
	 * @parameter default-value="${moduleClass}"
	 */
	private String moduleClass;
	
	/**
	 * @parameter default-value="${executables}"
	 */
	private String executables;
	
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
    
    /**
	 * @component
	 */
	private ArchiverManager archiverManager;
	
	public void execute() throws MojoExecutionException {
    	if ("jar".equals(project.getPackaging())) {
			PluginUtils.checkResolvedArtifacts(project, true);
			
			File buildDir = new File(project.getBuild().getDirectory());
	    	File outputDir = new File(project.getBuild().getOutputDirectory());
    	
        	if (!outputDir.exists())
        		outputDir.mkdirs();
        	
        	Properties props = new Properties();
        	props.put("id", project.getArtifact().getGroupId() + "." + project.getArtifact().getArtifactId());
        	props.put("version", project.getArtifact().getVersion());
        	
        	StringBuffer buffer = new StringBuffer();
        	List<Artifact> dependencies = new ArrayList<>(project.getDependencyArtifacts());
        	Collections.sort(dependencies, new Comparator<Artifact>() {

    			@Override
    			public int compare(Artifact o1, Artifact o2) {
    				return (o1.getGroupId() + "." + o1.getArtifactId() + ":" + o1.getVersion())
    						.compareTo(o2.getGroupId() + "." + o2.getArtifactId() + ":" + o2.getVersion());
    			}
        		
        	});
        	for (Artifact artifact: dependencies) {
        		if (PluginUtils.isRuntimeArtifact(artifact) 
        				&& PluginUtils.containsFile(artifact.getFile(), PluginConstants.ARTIFACT_PROPERTY_FILE)) {
    	    		buffer.append(artifact.getGroupId()).append(".").append(artifact.getArtifactId())
    	    			.append(":").append(artifact.getVersion()).append(";");
        		}
        	}
        	props.put("dependencies", buffer.toString());
        	
        	File propsFile = new File(outputDir, PluginConstants.ARTIFACT_PROPERTY_FILE);
            PluginUtils.writeProperties(propsFile, props);
        	
        	File bootstrapPropsFile = new File(outputDir, PluginConstants.BOOTSTRAP_PROPERTY_FILE);
        	if (bootstrap) 
        		PluginUtils.writeProperties(bootstrapPropsFile, new Properties());
        	
    		if (moduleClass != null) {
    	    	propsFile = new File(outputDir, PluginConstants.PLUGIN_PROPERTY_FILE);
    	    	props = new Properties();
    	    	props.put("name", project.getName());
    	    	if (project.getDescription() != null)
    	    		props.put("description", project.getDescription());
    	    	if (project.getOrganization() != null && project.getOrganization().getName() != null)
    	    		props.put("vendor", project.getOrganization().getName());
    	    	props.put("id", project.getArtifact().getGroupId() + "." + project.getArtifact().getArtifactId());
    	    	props.put("version", project.getArtifact().getVersion());
    	    	props.put("module", moduleClass);

    	    	buffer = new StringBuffer();
    	    	dependencies = new ArrayList<>(project.getDependencyArtifacts());
    	    	Collections.sort(dependencies, new Comparator<Artifact>() {

    				@Override
    				public int compare(Artifact o1, Artifact o2) {
    					return (o1.getGroupId() + "." + o1.getArtifactId()).compareTo(o2.getGroupId() + "." + o2.getArtifactId());
    				}
    	    		
    	    	});
    	    	for (Artifact artifact: dependencies) {
    	    		if (PluginUtils.isRuntimeArtifact(artifact) 
    	    				&& PluginUtils.containsFile(artifact.getFile(), PluginConstants.PLUGIN_PROPERTY_FILE)) {
    	    			buffer.append(artifact.getGroupId() + "." + artifact.getArtifactId()).append(";");
    	    		}
    	    	}
    	    	props.put("dependencies", buffer.toString());

    	    	PluginUtils.writeProperties(propsFile, props);    	
    		}
    		
    		File sandboxDir = new File(buildDir, PluginConstants.SANDBOX);
    		if (executables != null) {
    	    	props = new Properties();
    	    	props.put("executables", executables);

    	    	propsFile = new File(outputDir, PluginConstants.PRODUCT_PROPERTY_FILE); 
    	    	PluginUtils.writeProperties(propsFile, props);
    	    	
    	    	File bootDir = new File(sandboxDir, "boot");
    	    	if (!bootDir.exists())
    	    		bootDir.mkdirs();

    	    	Set<String> bootstrapKeys = new HashSet<String>();

    	    	for (Artifact artifact: PluginUtils.getBootstrapArtifacts(project, repoSystem, repoSession, remoteRepos))
    	    		bootstrapKeys.add(PluginUtils.getArtifactKey(artifact));
    	    	
    	    	PluginUtils.writeObject(new File(bootDir, PluginConstants.BOOTSTRAP_KEYS), bootstrapKeys);
    	    	
    	    	PluginUtils.writeClasspath(new File(bootDir, PluginConstants.SYSTEM_CLASSPATH), project, 
    	    			repoSystem, repoSession, remoteRepos);

    	    	File systemDir = new File(project.getBasedir(), "system");
    	    	for (String path: PluginUtils.listFiles(systemDir, null, null)) {
    	    		File file = new File(systemDir, path);
    				File destFile = new File(sandboxDir, path); 
    				if (file.isDirectory()) {
    					if (!destFile.exists())
    						destFile.mkdirs();
    				} else if (!destFile.exists() || destFile.lastModified() < file.lastModified()) {
    					try {
    						FileUtils.copyFile(file, destFile);
    					} catch (IOException e) {
    						throw new RuntimeException(e);
    					}
    					destFile.setLastModified(file.lastModified());
    				}
    	    	}
    	    	
    			try {
    				File versionFile = new File(sandboxDir, "version.txt");
    				if (!versionFile.exists() || !project.getVersion().equals(FileUtils.fileRead(versionFile).trim())) {
    					Echo echo = new Echo();
    					echo.setProject(PluginUtils.newAntProject(getLog()));
    					echo.setFile(versionFile);
    					echo.setMessage(project.getVersion());
    					echo.execute();
    				}
    			} catch (BuildException | IOException e) {
    				throw new RuntimeException(e);
    			}
    		} else {
    	    	for (Artifact artifact: project.getArtifacts()) {
    	    		if (PluginUtils.isRuntimeArtifact(artifact)) {
	    	    		Properties productProps = PluginUtils.loadProperties(
	    	    				artifact.getFile(), PluginConstants.PRODUCT_PROPERTY_FILE);
	    	    		if (productProps != null) {
	    	    			if (!sandboxDir.exists()) {
	    	    	    		if (artifact.getFile().isFile()) {
	    	    	    			UnArchiver unArchiver;
	    	    	    			try {
	    								unArchiver = archiverManager.getUnArchiver(artifact.getFile());
	    							} catch (NoSuchArchiverException e) {
	    								throw new RuntimeException(e);
	    							}
	    	    	    			unArchiver.setSourceFile(artifact.getFile());
	    	    	    			unArchiver.setDestDirectory(buildDir);
	    		        	    	unArchiver.setOverwrite(true);
	    		        	    	IncludeExcludeFileSelector[] selectors = new IncludeExcludeFileSelector[] {new IncludeExcludeFileSelector()};
	    		        	    	selectors[0].setIncludes(new String[]{PluginConstants.SANDBOX + "/**"});
	    		        	    	unArchiver.setFileSelectors(selectors);
	    		        	    	unArchiver.extract();
	    	    	    		} else {
	    	    					File srcDir = new File(artifact.getFile().getParentFile(), PluginConstants.SANDBOX);
	    	        				try {
	    	    						FileUtils.copyDirectoryStructure(srcDir, sandboxDir);
	    	    					} catch (IOException e) {
	    	    						throw new RuntimeException(e);
	    	    					}
	    	    	    		}
	    	    			}
	    	    	    	File bootDir = new File(sandboxDir, "boot");
	    	    	    	PluginUtils.writeClasspath(new File(bootDir, PluginConstants.SYSTEM_CLASSPATH), project, 
	    	    	    			repoSystem, repoSession, remoteRepos);
	    	    			break;
	    	    		}
    	    		}
    	    	}			
    		}    		
    	}
    }
	
}
