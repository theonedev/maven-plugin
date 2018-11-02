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
 * @goal generate-plugin-descriptor
 * @requiresDependencyResolution compile+runtime
 */
public class GeneratePluginDescriptorMojo extends AbstractMojo {
	
	/**
     * @parameter default-value="${project}"
     * @required
     * @readonly
	 */
	private MavenProject project;
	
	/**
	 * @parameter default-value="${moduleClass}"
	 */
	private String moduleClass;

	public void execute() throws MojoExecutionException {
		if (moduleClass == null)
			return;
		
		PluginUtils.checkResolvedArtifacts(project, true);
		
    	File outputDir = new File(project.getBuild().getOutputDirectory());

    	if (!outputDir.exists())
    		outputDir.mkdirs();
    	
    	File propsFile = new File(outputDir, PluginConstants.PLUGIN_PROPERTY_FILE);
    	Properties props = new Properties();
    	props.put("name", project.getName());
    	if (project.getDescription() != null)
    		props.put("description", project.getDescription());
    	if (project.getOrganization() != null && project.getOrganization().getName() != null)
    		props.put("vendor", project.getOrganization().getName());
    	props.put("id", project.getArtifact().getGroupId() + "." + project.getArtifact().getArtifactId());
    	props.put("version", project.getArtifact().getVersion());
    	props.put("module", moduleClass);

    	StringBuffer buffer = new StringBuffer();
    	List<Artifact> dependencies = new ArrayList<>(project.getDependencyArtifacts());
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

    	if (!propsFile.exists() || !PluginUtils.loadProperties(propsFile).equals(props))
    		PluginUtils.writeProperties(propsFile, props);
    }
    
}
