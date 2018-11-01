package io.onedev.plugin.maven;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;

/**
 * @goal generate-artifact-descriptor
 * @requiresDependencyResolution compile+runtime
 */
public class GenerateArtifactDescriptorMojo extends AbstractMojo {
	
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

	public void execute() throws MojoExecutionException {
		PluginUtils.checkResolvedArtifacts(project, true);
		
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
    	if (!propsFile.exists() || !PluginUtils.loadProperties(propsFile).equals(props))
        	PluginUtils.writeProperties(propsFile, props);
    	
    	File bootstrapPropsFile = new File(outputDir, PluginConstants.BOOTSTRAP_PROPERTY_FILE);
    	if (bootstrap && !bootstrapPropsFile.exists()) 
    		PluginUtils.writeProperties(bootstrapPropsFile, new Properties());
    }
    
}
