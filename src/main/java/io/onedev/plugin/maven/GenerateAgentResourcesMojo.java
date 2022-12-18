package io.onedev.plugin.maven;

import java.io.File;
import java.util.Properties;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;

/**
 * Generate Agent Resources Mojo.
 */
@Mojo(name = "generate-agent-resources", requiresDependencyResolution = ResolutionScope.COMPILE_PLUS_RUNTIME)
public class GenerateAgentResourcesMojo extends AbstractMojo {
	
	@Parameter(readonly = true, required = true, defaultValue = "${project}")
	private MavenProject project;

	@Override
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
