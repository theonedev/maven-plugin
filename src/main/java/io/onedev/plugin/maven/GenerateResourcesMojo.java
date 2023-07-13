package io.onedev.plugin.maven;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.*;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.archiver.UnArchiver;
import org.codehaus.plexus.archiver.manager.ArchiverManager;
import org.codehaus.plexus.archiver.manager.NoSuchArchiverException;
import org.codehaus.plexus.components.io.fileselectors.IncludeExcludeFileSelector;
import org.codehaus.plexus.util.FileUtils;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.jgit.api.Git;

/**
 * Populate Resource Mojo.
 */
@Mojo(name = "generate-resources", requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class GenerateResourcesMojo extends AbstractMojo {
	
	@Parameter( readonly = true, required = true, defaultValue = "${project}")
	private MavenProject project;
	
	@Parameter(defaultValue = "${bootstrap}")
	private boolean bootstrap;

	@Parameter(defaultValue = "${moduleClass}")
	private String moduleClass;
	
	@Parameter(defaultValue = "${executables}")
	private String executables;

    /**
     * The entry point to Aether, i.e. the component doing all the work.
     */
	@Component
    private RepositorySystem repoSystem;

    /**
     * The current repository/network configuration of Maven.
     */
	@Parameter(readonly = true, required = true, defaultValue = "${repositorySystemSession}")
    private RepositorySystemSession repoSession;

    /**
     * The project's remote repositories to use for the resolution of project dependencies.
     */
	@Parameter(readonly = true, defaultValue = "${project.remoteProjectRepositories}")
    private List<RemoteRepository> remoteRepos;
    
	@Component
	private ArchiverManager archiverManager;

	@SuppressWarnings("deprecation")
	@Override
	public void execute() throws MojoExecutionException {
    	if ("jar".equals(project.getPackaging())) {
			PluginUtils.checkResolvedArtifacts(project, true);

			File buildDir = new File(project.getBuild().getDirectory());
	    	File outputDir = new File(project.getBuild().getDirectory(), "generated-resources");
    	
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
				props.put("timestamp", String.valueOf(System.currentTimeMillis()));
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
    			Properties agentProps = null;
    	    	for (Artifact artifact: project.getArtifacts()) {
    	    		if (PluginUtils.isRuntimeArtifact(artifact)) {
	    	    		agentProps = PluginUtils.readProperties(
	    	    				artifact.getFile(), PluginConstants.AGENT_PROPERTY_FILE);
	    	    		if (agentProps != null) 
	    	    			break;
    	    		}
    	    	}
    	    	if (agentProps != null) {
    	    		Map<String, String> agentDependencyVersions = new HashMap<>();
    	    		for (String dependency: Splitter.on(";").omitEmptyStrings().split(agentProps.getProperty("dependencies"))) {
    	    			int index = dependency.indexOf(':');
    	    			agentDependencyVersions.put(dependency.substring(0, index), dependency.substring(index+1));
    	    		}
        	    	for (Artifact artifact: project.getArtifacts()) {
        	    		if (PluginUtils.isRuntimeArtifact(artifact)) {
            	    		String dependency = artifact.getGroupId() + "." + artifact.getArtifactId();
            	    		String version = agentDependencyVersions.get(dependency);
            	    		if (version != null && !version.equals(artifact.getVersion())) { 
            	    			String errorMessage = String.format(
            	    					"Inconsistent dependency version in agent and product (groupId: %s, artifactId: %s, version in agent: %s, version in product: %s)", 
            	    					artifact.getGroupId(), artifact.getArtifactId(), version, artifact.getVersion());
            	    			throw new RuntimeException(errorMessage);
            	    		}
        	    		}
        	    	}    	    		
    	    	}
    	    	
    	    	props = new Properties();
    	    	props.put("executables", executables);

    	    	propsFile = new File(outputDir, PluginConstants.PRODUCT_PROPERTY_FILE); 
    	    	PluginUtils.writeProperties(propsFile, props);
    	    	
    	    	File bootDir = new File(sandboxDir, "boot");
    	    	if (!bootDir.exists())
    	    		bootDir.mkdirs();

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

    	    	Properties releaseProps = new Properties();
    	    	File gitDir = new File(project.getBasedir().getParentFile(), ".git");
    	    	if (gitDir.exists()) {
        	    	try (Git git = Git.open(gitDir)) {
        	    		releaseProps.put("commit", git.getRepository().resolve("HEAD").name());
        	    	} catch (IOException e) {
        	    		throw new RuntimeException(e);
    				}
    	    	}
    	    	
    	    	File buildNumberFile = new File(project.getBasedir().getParentFile(), "build_number");
    	    	if (buildNumberFile.exists()) {
    	    		try {
						releaseProps.put("build", FileUtils.fileRead(buildNumberFile).trim());
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
    	    	}
    	    	
    	    	releaseProps.put("date", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()));
    	    	releaseProps.put("version", project.getVersion());
    	    	
				File releasePropsFile = new File(sandboxDir, "release.properties");
				if (releasePropsFile.exists()) {
					try (InputStream is = new FileInputStream(releasePropsFile)) {
						Properties oldReleaseProps = new Properties();
						oldReleaseProps.load(is);
						if (oldReleaseProps.equals(releaseProps))
							releaseProps = null;
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				} 
				
				if (releaseProps != null) {
					try (OutputStream os = new FileOutputStream(releasePropsFile)) {
						releaseProps.store(os, null);
					} catch (IOException e) {
						throw new RuntimeException(e);
					}
				}
    		} else {
    	    	for (Artifact artifact: project.getArtifacts()) {
    	    		if (PluginUtils.isRuntimeArtifact(artifact)) {
	    	    		Properties productProps = PluginUtils.readProperties(
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
	    	    			break;
	    	    		}
    	    		}
    	    	}			
    		}    		
    	}
    }
	
}
