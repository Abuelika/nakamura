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
package org.sakaiproject.kernel.files.search;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.commons.json.JSONException;
import org.apache.sling.commons.json.io.JSONWriter;
import org.apache.sling.jcr.resource.JcrResourceConstants;
import org.sakaiproject.kernel.api.files.FileUtils;
import org.sakaiproject.kernel.api.files.FilesConstants;
import org.sakaiproject.kernel.api.search.SearchResultProcessor;
import org.sakaiproject.kernel.api.site.SiteService;
import org.sakaiproject.kernel.util.ExtendedJSONWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Formats the files search results.
 * 
 * @scr.component immediate="true" label="FileSearchResultProcessor"
 *                description="Formatter for file searches"
 * @scr.property name="service.vendor" value="The Sakai Foundation"
 * @scr.property name="sakai.search.processor" value="Files"
 * @scr.service 
 *              interface="org.sakaiproject.kernel.api.search.SearchResultProcessor"
 * @scr.reference name="SiteService"
 *                interface="org.sakaiproject.kernel.api.site.SiteService"
 */
public class FileSearchResultProcessor implements SearchResultProcessor {

	public static final Logger LOGGER = LoggerFactory
			.getLogger(FileSearchResultProcessor.class);
	private SiteService siteService;

	public void bindSiteService(SiteService siteService) {
		this.siteService = siteService;
	}

	public void unbindSiteService(SiteService siteService) {
		this.siteService = null;
	}

	public void writeNode(JSONWriter write, Node node) throws JSONException,
			RepositoryException {

		Session session = node.getSession();
		if (node.hasProperty("sling:internalRedirect")) {
			// This node points to another node.
			// Get that one.
			String path = node.getProperty("sling:internalRedirect")
					.getString();
			if (session.itemExists(path)) {
				Node n = (Node) session.getItem(path);
				writeFileNode(write, n, session);
			}
		} else {
			writeFileNode(write, node, session);
		}
	}

	/**
	 * Output the file node in json format.
	 * 
	 * @param write
	 * @param node
	 * @param session
	 * @throws JSONException
	 * @throws RepositoryException
	 */
	private void writeFileNode(JSONWriter write, Node node, Session session)
			throws JSONException, RepositoryException {
		String type = node.getProperty(
				JcrResourceConstants.SLING_RESOURCE_TYPE_PROPERTY).getString();

		// If it is a file node we provide some extra properties.
		if (FilesConstants.RT_SAKAI_FILE.equals(type)) {
			FileUtils.writeFileNode(node, session, write, siteService);

		} else if (FilesConstants.RT_SAKAI_LINK.equals(type)) {
			// This is a linked file.
			FileUtils.writeLinkNode(node, session, write, siteService);

		} else if (FilesConstants.RT_SAKAI_FOLDER.equals(type)) {
			write.object();
			// dump all the properties.
			ExtendedJSONWriter.writeNodeContentsToWriter(write, node);
			write.key("path");
			write.value(node.getPath());
			write.key("name");
			write.value(node.getName());
			write.endObject();
		}
	}
}
