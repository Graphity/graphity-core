/*
 * Copyright 2016 Martynas Jusevičius <martynas@graphity.org>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.atomgraph.core.model.impl;

import com.atomgraph.core.model.Service;
import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.filter.HTTPBasicAuthFilter;
import org.apache.jena.rdf.model.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 *
 * @author Martynas Jusevičius <martynas@graphity.org>
 */
public class ServiceImpl implements Service
{

    private static final Logger log = LoggerFactory.getLogger(ServiceImpl.class);
    
    private final Resource sparqlEndpoint, graphStore;
    private final String authUser, authPwd;

    public ServiceImpl(Resource sparqlEndpoint, Resource graphStore, String authUser, String authPwd)
    {
	if (sparqlEndpoint == null) throw new IllegalArgumentException("SPARQLEndpoint Resource must be not null");
	if (graphStore == null) throw new IllegalArgumentException("Graph Store Resource must be not null");
        
        this.sparqlEndpoint = sparqlEndpoint;
        this.graphStore = graphStore;
        this.authUser = authUser;
        this.authPwd = authPwd;
    }

    public ServiceImpl(Resource sparqlEndpoint, Resource graphStore)
    {
        this(sparqlEndpoint, graphStore, null, null);
    }
    
    @Override
    public Resource getSPARQLEndpoint()
    {
        return sparqlEndpoint;
    }

    @Override
    public WebResource getSPARQLEndpointOrigin(Client client)
    {
	if (client == null) throw new IllegalArgumentException("Client must be not null");

        WebResource origin = client.resource(getSPARQLEndpoint().getURI());

        if (getAuthUser() != null && getAuthPwd() != null)
            origin.addFilter(new HTTPBasicAuthFilter(getAuthUser(), getAuthPwd())); 
        
        return origin;
    }
    
    @Override
    public Resource getGraphStore()
    {
        return graphStore;
    }

    @Override
    public WebResource getGraphStoreOrigin(Client client)
    {
	if (client == null) throw new IllegalArgumentException("Client must be not null");

        WebResource origin = client.resource(getGraphStore().getURI());

        if (getAuthUser() != null && getAuthPwd() != null)
            origin.addFilter(new HTTPBasicAuthFilter(getAuthUser(), getAuthPwd())); 
        
        return origin;
    }

    @Override
    public String getAuthUser()
    {
        return authUser;
    }
    
    @Override
    public String getAuthPwd()
    {
        return authPwd;
    }
    
}
