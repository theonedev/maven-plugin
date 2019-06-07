package io.onedev.plugin.maven;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.DefaultArtifact;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.apache.maven.project.MavenProject;
import org.apache.tools.ant.BuildEvent;
import org.apache.tools.ant.BuildListener;
import org.apache.tools.ant.DirectoryScanner;
import org.apache.tools.ant.Project;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.util.FileUtils;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.StringUtils;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;

public class PluginUtils {
	
	public static boolean containsFile(File file, String path) {
		if (file.isFile() && file.getName().endsWith(".jar")) {
			JarFile jar = null;
			try {
				jar = new JarFile(file);
				return jar.getJarEntry(path) != null; 
			} catch (Exception e) {
				throw unchecked(e);
			} finally {
				if (jar != null) {
					try {
						jar.close();
					} catch (IOException e) {
					}
				}
			}
		} else if (file.isDirectory() && new File(file, path).exists()) {
			return true;
		} else { 
			return false;
		}
	}
	
	public static Properties loadProperties(File file, String path) {
		if (file.isFile() && file.getName().endsWith(".jar")) {
			JarFile jar = null;
			try {
				jar = new JarFile(file);
				JarEntry entry = jar.getJarEntry(path);
				if (entry != null) {
					InputStream is = null;
					try {
						is = jar.getInputStream(entry);
						Properties props = new Properties();
						props.load(is);
						return props;
					} catch (IOException e) {
						throw new RuntimeException(e);
					} finally {
						if (is != null) {
							is.close();
						}
					}
				} else {
					return null;
				}
			} catch (Exception e) {
				throw unchecked(e);
			} finally {
				if (jar != null) {
					try {
						jar.close();
					} catch (IOException e) {
					}
				}
			}
		} else if (file.isDirectory() && new File(file, path).exists()) {
			Properties props = new Properties();
			InputStream is = null;
			try {
				is = new FileInputStream(new File(file, path));
				props.load(is);
				return props;
			} catch (IOException e) {
				throw new RuntimeException(e);
			} finally {
				if (is != null) {
					try {
						is.close();
					} catch (IOException e) {
					}
				}
			}
		} else {
			return null;
		}
	}

	public static boolean isRuntimeArtifact(Artifact artifact) {
		return artifact.getScope().equals(Artifact.SCOPE_COMPILE) || artifact.getScope().equals(Artifact.SCOPE_RUNTIME) 
				|| artifact.getScope().equals(Artifact.SCOPE_SYSTEM);
	}
	
	public static void writeClasspath(File file, MavenProject project, RepositorySystem repoSystem, 
			RepositorySystemSession repoSession, List<RemoteRepository> remoteRepos) throws MojoExecutionException {
		
    	Map<String, File> classpath = new HashMap<String, File>();
    	
    	for (Artifact artifact: project.getArtifacts()) { 
    		if (isRuntimeArtifact(artifact)) 
    			classpath.put(getArtifactKey(artifact), artifact.getFile());
    	}
    	
    	for (Artifact artifact: getBootstrapArtifacts(project, repoSystem, repoSession, remoteRepos))
    		classpath.put(getArtifactKey(artifact), artifact.getFile());

    	classpath.put(getArtifactKey(project.getArtifact()), new File(project.getBuild().getOutputDirectory()));
    	
    	writeObject(file, classpath);
	}
	
	public static Collection<Artifact> getBootstrapArtifacts(MavenProject project, RepositorySystem repoSystem, 
			RepositorySystemSession repoSession, List<RemoteRepository> remoteRepos) {
		Collection<Artifact> bootArtifacts = new HashSet<Artifact>();
		for (Artifact each: project.getArtifacts()) {
			if (containsFile(each.getFile(), PluginConstants.BOOTSTRAP_PROPERTY_FILE)) {
				bootArtifacts.add(each);
				org.eclipse.aether.artifact.Artifact aetherArtifact = new org.eclipse.aether.artifact.DefaultArtifact(
						each.getGroupId(), each.getArtifactId(), each.getClassifier(), each.getType(), 
						each.getVersion());
                CollectRequest collectRequest = new CollectRequest(new Dependency(aetherArtifact, null), 
                		new ArrayList<Dependency>(), remoteRepos);
                DependencyRequest dependencyRequest = new DependencyRequest(collectRequest, null);
                try {
					for (ArtifactResult result: repoSystem.resolveDependencies(repoSession, dependencyRequest).getArtifactResults()) {
						Dependency dependency = result.getRequest().getDependencyNode().getDependency();
						if (dependency.getArtifact()  != null && dependency.getArtifact().getFile() != null) {
							aetherArtifact = dependency.getArtifact();
							Artifact artifact = new DefaultArtifact(aetherArtifact.getGroupId(), aetherArtifact.getArtifactId(), 
									aetherArtifact.getVersion(), dependency.getScope(), 
									aetherArtifact.getExtension(), aetherArtifact.getClassifier(), null);
							artifact.setFile(aetherArtifact.getFile());
							if (isRuntimeArtifact(artifact))
								bootArtifacts.add(artifact);
						}
					}
				} catch (Exception e) {
					throw PluginUtils.unchecked(e);
				}
			}
		}
		return bootArtifacts;
	}
	
	public static Project newAntProject(Log log) {
		Project antProject = new Project();
		antProject.init();
		antProject.addBuildListener(new BuildListener(){

			@Override
			public void messageLogged(BuildEvent event) {
				if (event.getPriority() == Project.MSG_ERR)
					log.error(event.getMessage());
				else if (event.getPriority() == Project.MSG_WARN)
					log.warn(event.getMessage());
				else if (event.getPriority() == Project.MSG_INFO)
					log.info(event.getMessage());
				else
					log.debug(event.getMessage());
			}

			@Override
			public void buildFinished(BuildEvent event) {
			}

			@Override
			public void buildStarted(BuildEvent event) {
			}

			@Override
			public void targetFinished(BuildEvent event) {
			}

			@Override
			public void targetStarted(BuildEvent event) {
			}

			@Override
			public void taskFinished(BuildEvent event) {
			}

			@Override
			public void taskStarted(BuildEvent event) {
			}
			
		});
		return antProject;
	}
	
	@SuppressWarnings("unchecked")
	public static void populateArtifacts(MavenProject project, File sandboxDir, RepositorySystem repoSystem, 
			RepositorySystemSession repoSession, List<RemoteRepository> remoteRepos) {
		
		if (project.getArtifact().getFile() == null)
			throw new RuntimeException("Project artifact not generated yet."); 

		// copy necessary library files to sandbox so that sandbox can 
		// be executed from command line
		File bootDir = new File(sandboxDir, "boot");
		File libDir = new File(sandboxDir, "lib");

		if (!bootDir.exists())
			bootDir.mkdirs();
		if (!libDir.exists())
			libDir.mkdirs();
		
		Set<String> bootstrapKeys = (Set<String>) readObject(
				new File(bootDir, PluginConstants.BOOTSTRAP_KEYS));

		Set<Artifact> artifacts = new HashSet<Artifact>();
		for (Artifact artifact: project.getArtifacts()) {
			if (isRuntimeArtifact(artifact))
				artifacts.add(artifact);
		}
		for (Artifact artifact: getBootstrapArtifacts(project, repoSystem, repoSession, remoteRepos))
			artifacts.add(artifact);
		artifacts.add(project.getArtifact());
		
    	for (Artifact artifact: artifacts) {
    		String artifactKey = getArtifactKey(artifact);

			File destFile;
			if (bootstrapKeys.contains(artifactKey))
				destFile = new File(bootDir, artifactKey);
			else
				destFile = new File(libDir, artifactKey);
			try {
				FileUtils.copyFile(artifact.getFile(), destFile);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
    	}
	}
	
	public static Logger toLogger(final Log log, final String name) {
		return new Logger() {

			@Override
			public void debug(String message) {
				log.debug(message);
			}

			@Override
			public void debug(String message, Throwable throwable) {
				log.debug(message, throwable);
			}

			@Override
			public boolean isDebugEnabled() {
				return log.isDebugEnabled();
			}

			@Override
			public void info(String message) {
				log.info(message);
			}

			@Override
			public void info(String message, Throwable throwable) {
				log.info(message, throwable);
			}

			@Override
			public boolean isInfoEnabled() {
				return log.isInfoEnabled();
			}

			@Override
			public void warn(String message) {
				log.warn(message);
			}

			@Override
			public void warn(String message, Throwable throwable) {
				log.warn(message, throwable);
			}

			@Override
			public boolean isWarnEnabled() {
				return log.isWarnEnabled();
			}

			@Override
			public void error(String message) {
				log.error(message);
			}

			@Override
			public void error(String message, Throwable throwable) {
				log.error(message, throwable);
			}

			@Override
			public boolean isErrorEnabled() {
				return log.isErrorEnabled();
			}

			@Override
			public void fatalError(String message) {
				log.error(message);
			}

			@Override
			public void fatalError(String message, Throwable throwable) {
				log.error(message, throwable);
			}

			@Override
			public boolean isFatalErrorEnabled() {
				return log.isErrorEnabled();
			}

			@Override
			public int getThreshold() {
				return 0;
			}

			@Override
			public void setThreshold(int threshold) {
			}

			@Override
			public Logger getChildLogger(String name) {
				return this;
			}

			@Override
			public String getName() {
				return name;
			}
			
		};
	}
	
	public static void writeProperties(File file, Properties props) {
    	if (!file.exists() || !loadProperties(file).equals(props)) {
    		if (!file.getParentFile().exists())
    			file.getParentFile().mkdirs();
        	OutputStream os = null;
        	try {
        		os = new FileOutputStream(file);
        		props.store(os, null);
        	} catch (Exception e) {
        		throw unchecked(e);
    		} finally {
    			if (os != null) {
    				try {
    					os.close();
    				} catch (IOException e) {
    				}
    			}
        	}
    	}
	}
	
	public static Properties loadProperties(File file) {
		Properties props = new Properties();
		
		InputStream is = null;
		try {
			is = new FileInputStream(file);
			props.load(is);
			return props;
		} catch (Exception e) {
			throw unchecked(e);
		} finally {
			if (is != null) {
				try {
					is.close();
				} catch (IOException e) {
				}
			}
		}
	}
	
	public static void writeObject(File file, Object obj) {
		if (!file.exists() || !obj.equals(readObject(file))) {
	    	ObjectOutputStream oos = null;
	    	try {
	    		oos = new ObjectOutputStream(new FileOutputStream(file));
	    		oos.writeObject(obj);
	    	} catch (Exception e) {
	    		throw unchecked(e);
			} finally {
				if (oos != null) {
					try {
						oos.close();
					} catch (IOException e) {
					}
				}
	    	}
		}
	}
	
	public static Object readObject(File file) {
    	ObjectInputStream ois = null;
    	try {
    		ois = new ObjectInputStream(new FileInputStream(file));
    		return ois.readObject();
    	} catch (Exception e) {
			throw unchecked(e);
		} finally {
			if (ois != null) {
				try {
					ois.close();
				} catch (IOException e) {
				}
			}
    	}
	}
	
	public static RuntimeException unchecked(Throwable e) {
		if (e instanceof RuntimeException)
			throw (RuntimeException)e;
		else
			throw new RuntimeException(e);
	}

	public static String getArtifactKey(Artifact artifact) {
		return artifact.getGroupId() + "." + artifact.getArtifactId() + "-" + artifact.getVersion() 
				+ (artifact.hasClassifier()? "-" + artifact.getClassifier():"") + "." + artifact.getType();
	}
	
	public static Collection<String> splitAndTrim(String str, String separator) {
		Collection<String> fields = new ArrayList<String>();
		for (String each: StringUtils.split(str, separator)) {
			if (each != null && each.trim().length() != 0)
				fields.add(each.trim());
		}
		return fields;
	}
	
	public static void checkResolvedArtifacts(MavenProject project, boolean onlyRuntime) {
		for (Artifact artifact: project.getArtifacts()) {
			if ((onlyRuntime && isRuntimeArtifact(artifact) || !onlyRuntime) && artifact.getFile() == null) {
				throw new RuntimeException("Failed to resolve artifact '" + artifact 
						+ "', please check if it has been relocated.");
			}
		}
		for (Artifact artifact: project.getDependencyArtifacts()) {
			if ((onlyRuntime && isRuntimeArtifact(artifact) || !onlyRuntime) && artifact.getFile() == null) {
				throw new RuntimeException("Failed to resolve artifact '" + artifact 
						+ "', please check if it has been relocated.");
			}
		}
	}

	public static List<String> listFiles(File baseDir, String[] includes, String[] excludes) {
    	DirectoryScanner scanner = new DirectoryScanner();
    	scanner.setBasedir(baseDir);
    	if (includes != null)
    		scanner.setIncludes(includes);
    	if (excludes != null)
    		scanner.setExcludes(excludes);
    	scanner.scan();		    	
    	List<String> paths = new ArrayList<>();
    	for (String path: scanner.getIncludedDirectories()) {
    		if (path.length() != 0)
    			paths.add(path);
    	}
    	for (String path: scanner.getIncludedFiles())
    		paths.add(path);
    	return paths;
	}
	
	public static void addFileToJar(JarOutputStream jos, File file, String entryName) {
		if (File.separatorChar != '/')
			entryName = entryName.replace('\\', '/');
		
		if (file.isDirectory())
			entryName += "/";

		try {
			jos.putNextEntry(new JarEntry(entryName));
		} catch (IOException e) {
			throw new RuntimeException(e);
		}

		if (file.isFile()) {
			try {
				FileInputStream is = null;
				try {
					is = new FileInputStream(file);
					IOUtil.copy(is, jos);
				} finally {
					IOUtil.close(is);
				}

				jos.flush();
				jos.closeEntry();
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
		}
	}

}
