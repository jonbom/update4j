/*
 * Copyright 2018 Mordechai Meisels
 * 
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 * 
 * 		http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.update4j.service;

import org.update4j.Bootstrap;
import org.update4j.Configuration;
import org.update4j.SingleInstanceManager;
import org.update4j.Update;
import org.update4j.util.ArgUtils;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.PublicKey;
import java.security.cert.CertificateFactory;
import java.util.List;
import java.util.Map;

public class DefaultBootstrap implements Delegate {

	private String remote;
	private String local;
	private String cert;

	private boolean syncLocal;
	private boolean deleteOldFiles;
	private boolean launchFirst;
	private boolean stopOnUpdateError;
	private boolean singleInstance;

	private PublicKey pk = null;

	@Override
	public long version() {
		return Long.MIN_VALUE;
	}

	@Override
	public void main(List<String> args) throws Throwable {
		if (args.isEmpty()) {
			welcome();
			return;
		}

		parseArgs(ArgUtils.beforeSeparator(args));

		if (remote == null && local == null) {
			throw new IllegalArgumentException("One of --remote or --local must be supplied.");
		}

		if (launchFirst && local == null) {
			throw new IllegalArgumentException("--launchFirst requires a local configuration.");
		}

		if (syncLocal && remote == null) {
			throw new IllegalArgumentException("--syncLocal requires a remote configuration.");
		}

		if (syncLocal && local == null) {
			throw new IllegalArgumentException("--syncLocal requires a local configuration.");
		}

		if (singleInstance) {
			SingleInstanceManager.execute();
		}

		if (cert != null) {
			CertificateFactory cf = CertificateFactory.getInstance("X.509");
			try (InputStream in = Files.newInputStream(Paths.get(cert))) {
				pk = cf.generateCertificate(in).getPublicKey();
			}
		}

		List<String> businessArgs = ArgUtils.afterSeparator(args);

		if (launchFirst) {
			launchFirst(businessArgs);
		} else {
			updateFirst(businessArgs);
		}
	}

	private void parseArgs(List<String> bootArgs) {

		Map<String, String> parsed = ArgUtils.parseArgs(bootArgs);
		for (Map.Entry<String, String> e : parsed.entrySet()) {
			String arg = e.getKey();

			if ("syncLocal".equals(arg)) {
				validateNotSet(syncLocal, "syncLocal");
				ArgUtils.validateNoValue(e);
				syncLocal = true;
				continue;
			}
			if ("launchFirst".equals(arg)) {
				validateNotSet(launchFirst, "launchFirst");
				ArgUtils.validateNoValue(e);
				launchFirst = true;
				continue;
			}
			if ("stopOnUpdateError".equals(arg)) {
				validateNotSet(stopOnUpdateError, "stopOnUpdateError");
				ArgUtils.validateNoValue(e);
				stopOnUpdateError = true;
				continue;
			}
			if ("singleInstance".equals(arg)) {
				validateNotSet(singleInstance, "singleInstance");
				ArgUtils.validateNoValue(e);
				singleInstance = true;
				continue;
			}
			if ("remote".equals(arg)) {
				validateNotSet(remote, "remote");
				ArgUtils.validateHasValue(e);
				remote = e.getValue();
				continue;
			}
			if ("local".equals(arg)) {
				validateNotSet(local, "local");
				ArgUtils.validateHasValue(e);
				local = e.getValue();
				continue;
			}
			if ("cert".equals(arg)) {
				validateNotSet(cert, "cert");
				ArgUtils.validateHasValue(e);
				cert = e.getValue();
				continue;
			}
			if ("deleteOldFiles".equals(arg)) {
				validateNotSet(deleteOldFiles, "deleteOldFiles");
				ArgUtils.validateNoValue(e);
				deleteOldFiles = true;
				continue;
			}
			if ("delegate".equals(arg)) {
				continue; // ignore;
			}

			throw new IllegalArgumentException(
							"Unknown option \"" + arg + "\". Separate business app arguments with '--'.");
		}
	}

	private void validateNotSet(boolean val, String command) {
		if (val)
			throw new IllegalArgumentException("Duplicate '--" + command + "' command.");
	}

	private void validateNotSet(String val, String command) {
		if (val != null)
			throw new IllegalArgumentException("Duplicate '--" + command + "' command.");
	}

	private void updateFirst(List<String> args) throws Throwable {
		Configuration remoteConfig = null;
		Configuration localConfig = null;

		if (remote != null) {
			remoteConfig = getRemoteConfig();
		}

		if (local != null) {
			localConfig = getLocalConfig(remote != null && syncLocal);
		}

		if (remoteConfig == null && localConfig == null) {
			return;
		}

		Configuration config = remoteConfig != null ? remoteConfig : localConfig;
		boolean failedRemoteUpdate = false;

		if (config.requiresUpdate()) {
			boolean success = config.update(pk);
			if (config == remoteConfig)
				failedRemoteUpdate = !success;

			if (!success && stopOnUpdateError) {
				return;
			}
		}

		if (!failedRemoteUpdate && remoteConfig != null && !remoteConfig.equals(localConfig)) {
			if (syncLocal) {
				syncLocal(remoteConfig);
			}
			if (deleteOldFiles) {
				deletOldFiles(remoteConfig, localConfig);
			}
		}

		config.launch(args);

	}

	private void deletOldFiles(Configuration remoteConfig, Configuration localConfig) throws IOException {
		remoteConfig.deleteOldFiles(localConfig);
	}

	private void launchFirst(List<String> args) throws Throwable {
		Path tempDir = Paths.get("update");
		if (Update.containsUpdate(tempDir)) {
			Update.finalizeUpdate(tempDir);
		}

		Configuration localConfig = null;
		localConfig = getLocalConfig(false);

		boolean localNotReady = localConfig == null || localConfig.requiresUpdate();

		if (!localNotReady) {
			Configuration finalConfig = localConfig;
			Thread localApp = new Thread(() -> finalConfig.launch(args));
			localApp.run();
		}

		Configuration remoteConfig = null;
		if (remote != null) {
			remoteConfig = getRemoteConfig();
		}

		boolean failedRemoteUpdate = false;

		if (localNotReady) {
			Configuration config = remoteConfig != null ? remoteConfig : localConfig;

			if (config != null) {
				boolean success = !config.update(pk);
				if (config == remoteConfig)
					failedRemoteUpdate = !success;

				if (!success && stopOnUpdateError) {
					return;
				}

				config.launch(args);
			}
		} else if (remoteConfig != null) {
			if (remoteConfig.requiresUpdate()) {
				failedRemoteUpdate = !remoteConfig.updateTemp(tempDir, pk);
			}
		}

		if (!failedRemoteUpdate && remoteConfig != null && !remoteConfig.equals(localConfig)) {
			syncLocal(remoteConfig);
			if (deleteOldFiles) {
				deletOldFiles(remoteConfig, localConfig);
			}
		}

	}

	private Reader openConnection(URL url) throws IOException {

		URLConnection connection = url.openConnection();

		// Some downloads may fail with HTTP/403, this may solve it
		connection.addRequestProperty("User-Agent", "Mozilla/5.0");
		// Set a connection timeout of 10 seconds
		connection.setConnectTimeout(10 * 1000);
		// Set a read timeout of 10 seconds
		connection.setReadTimeout(10 * 1000);

		return new InputStreamReader(connection.getInputStream());
	}

	private Configuration getLocalConfig(boolean ignoreFileNotFound) {
		try (Reader in = Files.newBufferedReader(Paths.get(local))) {
			if (pk == null) {
				return Configuration.read(in);
			} else {
				return Configuration.read(in, pk);

			}
		} catch (NoSuchFileException e) {
			if (!ignoreFileNotFound) {
				e.printStackTrace();
			}
		} catch (Exception e) {
			// All exceptions just returns null, never fail
			e.printStackTrace();
		}

		return null;
	}

	private Configuration getRemoteConfig() {
		try (Reader in = openConnection(new URL(remote))) {
			if (pk == null) {
				return Configuration.read(in);
			} else {
				return Configuration.read(in, pk);

			}
		} catch (Exception e) {
			e.printStackTrace();

		}

		return null;
	}

	private void syncLocal(Configuration remoteConfig) {
		Path localPath = Paths.get(local);
		try {
			if (localPath.getParent() != null)
				Files.createDirectories(localPath.getParent());

			try (Writer out = Files.newBufferedWriter(localPath)) {
				remoteConfig.write(out);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	// @formatter:off
	private static void welcome() {

		System.out.println(getLogo() + "\tWelcome to the update4j framework.\n\n"
				+ "\tYou started the framework with its default bootstrap, which does\n"
				+ "\tthe update and launch logic for you without complex setup. All you need is to\n"
				+ "\tspecify some settings via command line arguments.\n\n"
				+ "\tBefore you start, you first need to create a \"configuration\" file that contains\n"
				+ "\tall details required to run. You can create one by using Configuration.builder()\n"
				+ "\tBuilder API. You can sync an existing configuration when files are changed\n"
				+ "\tusing one of the Configuration.sync() methods.\n\n"
				+ "\tFor more details how to create a configuration please refer to the Javadoc:\n"
				+ "\thttp://docs.update4j.org/javadoc/update4j/org/update4j/Configuration.html\n\n"
				+ "\tWhile the default bootstrap works perfectly for a majority of cases, you might\n"
				+ "\tfurther customize the update and launch life-cycle to the last detail by\n"
				+ "\timplementing a custom bootstrap and update/launch your business application\n"
				+ "\tusing the Configuration.update() and Configuration.launch() methods.\n\n"
				+ "\tIf you choose to implement your own bootstrap, there are 2 ways to do it:\n\n"
				+ "\t\t- Standard Mode: Start the bootstrap application using your own main method.\n"
				+ "\t\t  You will not be able to update the bootstrap application (as code cannot update itself),\n"
				+ "\t\t  only the business application will be updatable.\n\n"
				+ "\t\t- Delegate Mode: Move your main method into an implementation of Delegate\n"
				+ "\t\t  and start the framework just as you did now, i.e. calling update4j's main method.\n"
				+ "\t\t  This allows you to update the bootstrap application by releasing a newer version\n"
				+ "\t\t  with a higher version() number and make it visible to the JVM boot classpath\n"
				+ "\t\t  or modulepath by placing it in the right directory.\n"
				+ "\t\t  It is recommended not to use this feature before you can get everything\n"
				+ "\t\t  to run smoothly in Standard Mode, as this adds an extra layer of complexity.\n\n"
				+ "\tFor more details about implementing the bootstrap, please refer to the Github wiki:\n"
				+ "\thttps://github.com/update4j/update4j/wiki/Documentation#lifecycle\n"
				+ "\tFor more details how to register service providers please refer to the Github wiki:\n"
				+ "\thttps://github.com/update4j/update4j/wiki/Documentation#dealing-with-providers\n\n");

		usage();
	}

	private static String getLogo() {

		return

		"\n"
				+ "\t                 _       _          ___ _ \n"
				+ "\t                | |     | |        /   (_)\n"
				+ "\t _   _ _ __   __| | __ _| |_ ___  / /| |_ \n"
				+ "\t| | | | '_ \\ / _` |/ _` | __/ _ \\/ /_| | |\n"
				+ "\t| |_| | |_) | (_| | (_| | ||  __/\\___  | |\n"
				+ "\t \\__,_| .__/ \\__,_|\\__,_|\\__\\___|    |_/ |\n"
				+ "\t      | |                             _/ |\n"
				+ "\t      |_|                            |__/ \n\n\n"

		;

	}

	private static void usage() {

		System.err.println("To start in modulepath:\n\n"
				+ "\tjava -p update4j-" + Bootstrap.VERSION
				+ ".jar -m org.update4j [commands...] [-- business-args...]\n"
				+ "\tjava -p . -m org.update4j [commands...] [-- business-args...]\n\n"
				+ "To start in classpath:\n\n" + "\tjava -jar update4j-"
				+ Bootstrap.VERSION + ".jar [commands...] [-- business-args...]\n"
				+ "\tjava -cp update4j-" + Bootstrap.VERSION
				+ ".jar org.update4j.Bootstrap [commands...] [-- business-args...]\n"
				+ "\tjava -cp * org.update4j.Bootstrap [commands...] [-- business-args...]\n\n"
				+ "Available commands:\n\n"
				+ "\t--remote [url] - The remote (or if using file:/// scheme - local) location of the\n"
				+ "\t\tconfiguration file. If it fails to download or command is missing, it will\n"
				+ "\t\tfall back to local.\n\n"
				+ "\t--local [path] - The path of a local configuration to use if the remote failed to download\n"
				+ "\t\tor was not passed. If both remote and local fail, startup fails.\n\n"
				+ "\t--syncLocal - Sync the local configuration with the remote if it downloaded, loaded and\n"
				+ "\t\tupdated files successfully. Useful to still allow launching without Internet connection.\n"
				+ "\t\tDefault will not sync unless --launchFirst was specified.\n\n"
				+ "\t--cert [path] - A path to an X.509 certificate file to use to verify signatures. If missing,\n"
				+ "\t\tno signature verification will be performed.\n\n"
				+ "\t--launchFirst - If specified, it will first launch the local application then silently\n"
				+ "\t\tdownload the update; the update will be available only on next restart. It will still\n"
				+ "\t\tdownload the remote and update first if the local config requires an update\n"
				+ "\t\t(e.g. files were deleted). Must have a local configuration.\n"
				+ "\t\tIf not specified it will update before launch and hang the application until done.\n\n"
				+ "\t--deleteOldFiles - Will delete old files locally.\n"
				+ "\t\tThis deletes local files if remote config succeded to download.\n"
				+ "\t--stopOnUpdateError - Will stop the launch if an error occurred while downloading an update.\n"
				+ "\t\tThis does not include if remote failed to download and it used local as a fallback.\n"
				+ "\t\tIf --launchFirst was used, this only applies if the local config requires an update\n"
				+ "\t\tand failed.\n\n"
				+ "\t--singleInstance - Run the application as a single instance. Any subsequent attempts\n"
				+ "\t\tto run will just exit. You can better control this feature by directly using the\n"
				+ "\t\tSingleInstanceManager class.\n\n\n"
				+ "To pass arguments to the business application, separate them with '--' (w/o quotes).");

	}
}
