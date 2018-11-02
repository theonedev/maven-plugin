package io.onedev.plugin.maven;

import java.io.File;
import java.io.IOException;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.util.DirectoryScanner;
import org.codehaus.plexus.util.FileUtils;

/**
 * @goal copy-product-resources 
 */
public class CopyProductResourcesMojo extends AbstractMojo {
	
	/**
     * @parameter default-value="${project}"
     * @required
     * @readonly
	 */
	private MavenProject project;

	@Override
	public void execute() throws MojoExecutionException {
		DirectoryScanner scanner = new DirectoryScanner();
		File srcDir = new File(project.getBasedir(), "system");
		scanner.setBasedir(srcDir);
		scanner.setIncludes(new String[] {"**"});
		scanner.scan();

		File destDir = new File(project.getBuild().getDirectory(), PluginConstants.SANDBOX + "/system");
		for (String included: scanner.getIncludedFiles()) {
			File srcFile = new File(srcDir, included);
			File destFile = new File(destDir, included); 
			if (srcFile.isDirectory()) {
				if (!destFile.exists())
					destFile.mkdirs();
			} else if (!destFile.exists() || destFile.lastModified() < srcFile.lastModified()) {
				try {
					FileUtils.copyFile(srcFile, destFile);
				} catch (IOException e) {
					throw new RuntimeException(e);
				}
				destFile.setLastModified(srcFile.lastModified());
			}
		}
    }
    
}
