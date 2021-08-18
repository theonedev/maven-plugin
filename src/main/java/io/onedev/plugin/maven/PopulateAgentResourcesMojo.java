package io.onedev.plugin.maven;

import java.io.File;
import java.util.Properties;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;

/**
 * @goal populate-agent-resources
 * @requiresDependencyResolution compile+runtime
 */
public class PopulateAgentResourcesMojo extends AbstractMojo {
	
	/**
     * @parameter default-value="${project}"
     * @required
     * @readonly
	 */
	private MavenProject project;
	
	public void execute() throws MojoExecutionException {
    	if ("jar".equals(project.getPackaging())) {
			PluginUtils.checkResolvedArtifacts(project, true);
			
	    	File outputDir = new File(project.getBuild().getOutputDirectory());
    	
        	if (!outputDir.exists())
        		outputDir.mkdirs();
        	
        	Properties props = new Properties();
        	props.put("id", project.getArtifact().getGroupId() + "." + project.getArtifact().getArtifactId());
        	props.put("version", project.getArtifact().getVersion());
        	
        	StringBuffer buffer = new StringBuffer();
        	for (Artifact artifact: project.getArtifacts()) {
        		if (PluginUtils.isRuntimeArtifact(artifact)) {
    	    		buffer.append(artifact.getGroupId()).append(".")
    	    				.append(artifact.getArtifactId()).append(":")
    	    				.append(artifact.getVersion()).append(";");
        		}
        	}
        	props.put("dependencies", buffer.toString());
        	
        	File propsFile = new File(outputDir, PluginConstants.AGENT_PROPERTY_FILE);
            PluginUtils.writeProperties(propsFile, props);
    	}
    }
	
}
