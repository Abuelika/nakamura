/*
 * Licensed to the Sakai Foundation (SF) under one
 * or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership. The SF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */
package org.apache.sling.jcr.jackrabbit.server.impl.security.dynamic;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.SimpleCredentials;

import org.apache.commons.io.FileUtils;
import org.apache.jackrabbit.core.RepositoryImpl;
import org.apache.jackrabbit.core.config.RepositoryConfig;
import org.apache.jackrabbit.core.security.authorization.acl.RulesPrincipalProvider;
import org.osgi.framework.BundleContext;
import org.sakaiproject.nakamura.api.lite.accesscontrol.AccessDeniedException;
import org.sakaiproject.nakamura.api.lite.jackrabbit.SparseComponentHolder;
import org.sakaiproject.nakamura.api.lite.storage.ConnectionPoolException;
import org.sakaiproject.nakamura.api.lite.storage.StorageClientException;
import org.sakaiproject.nakamura.lite.ConfigurationImpl;
import org.sakaiproject.nakamura.lite.authorizable.AuthorizableActivator;
import org.sakaiproject.nakamura.lite.storage.StorageClient;
import org.sakaiproject.nakamura.lite.storage.mem.MemoryStorageClientConnectionPool;

import com.google.common.collect.Maps;

/**
 *
 */
public class RepositoryBase {
	private RepositoryImpl repository;
	private SakaiActivator sakaiActivator;
	private BundleContext bundleContext;
	private org.sakaiproject.nakamura.lite.RepositoryImpl sparseRepository;
	private ConfigurationImpl configuration;
	private MemoryStorageClientConnectionPool connectionPool;
	private StorageClient client;

	/**
   *
   */
	public RepositoryBase(BundleContext bundleContext) {
		this.bundleContext = bundleContext;
	}

	public void start() throws IOException, RepositoryException,
			ConnectionPoolException, StorageClientException,
			AccessDeniedException, ClassNotFoundException {
		File home = new File("target/testrepo");
		if (home.exists()) {
			FileUtils.deleteDirectory(home);
		}
		InputStream ins = this.getClass().getClassLoader()
				.getResourceAsStream("test-repository.xml");

		setupSakaiActivator();
		RepositoryConfig crc = RepositoryConfig.create(ins,
				home.getAbsolutePath());
		repository = RepositoryImpl.create(crc);
		Session session = repository.login(new SimpleCredentials("admin",
				"admin".toCharArray()));
		session.getWorkspace()
				.getNamespaceRegistry()
				.registerNamespace("sakai",
						"http://www.sakaiproject.org/nakamura/2.0");
		session.getWorkspace().getNamespaceRegistry()
				.registerNamespace("sling", "http://sling.apache.org/testing");
		if (session.hasPendingChanges()) {
			session.save();
		}
		session.logout();
	}

	/**
	 * @throws AccessDeniedException
	 * @throws StorageClientException
	 * @throws ConnectionPoolException
	 * @throws ClassNotFoundException 
	 * 
	 */
	private void setupSakaiActivator() throws ConnectionPoolException,
			StorageClientException, AccessDeniedException, ClassNotFoundException {
		System.err.println("Bundle is " + bundleContext);
		DynamicPrincipalManagerFactoryImpl dynamicPrincipalManagerFactoryImpl = new DynamicPrincipalManagerFactoryImpl(
				bundleContext);
		RuleProcessorManagerImpl ruleProcessorManagerImpl = new RuleProcessorManagerImpl(
				bundleContext);
		PrincipalProviderRegistryManagerImpl principalProviderRegistryManagerImpl = new PrincipalProviderRegistryManagerImpl(
				bundleContext);
		principalProviderRegistryManagerImpl
				.addProvider(new RulesPrincipalProvider());
		SakaiActivator
				.setDynamicPrincipalManagerFactory(dynamicPrincipalManagerFactoryImpl);
		SakaiActivator.setRuleProcessorManager(ruleProcessorManagerImpl);
		SakaiActivator
				.setPrincipalProviderManager(principalProviderRegistryManagerImpl);
		sakaiActivator = new SakaiActivator();
		sakaiActivator.start(bundleContext);

		
		// setup the Sparse Content Repository.
		connectionPool = new MemoryStorageClientConnectionPool();
		connectionPool.activate(new HashMap<String, Object>());
		client = connectionPool.openConnection();
		configuration = new ConfigurationImpl();
		Map<String, Object> properties = Maps.newHashMap();
		properties.put("keyspace", "n");
		properties.put("acl-column-family", "ac");
		properties.put("authorizable-column-family", "au");
		properties.put("content-column-family", "cn");
		configuration.activate(properties);
		AuthorizableActivator authorizableActivator = new AuthorizableActivator(
				client, configuration);
		authorizableActivator.setup();

		sparseRepository = new org.sakaiproject.nakamura.lite.RepositoryImpl();
		sparseRepository.setConfiguration(configuration);
		sparseRepository.setConnectionPool(connectionPool);
		sparseRepository.activate(new HashMap<String, Object>());
		SparseComponentHolder.setSparseRespository(sparseRepository);
	}

	public void stop() {
		repository.shutdown();
		sakaiActivator.stop(bundleContext);
	}

	/**
	 * @return the repository
	 */
	public RepositoryImpl getRepository() {
		return repository;
	}

}
