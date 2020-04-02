/*
 * Copyright 2015 Martynas Jusevičius <martynas@atomgraph.com>.
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

package com.atomgraph.core.provider;

import org.glassfish.hk2.api.Factory;
import javax.inject.Singleton;
import javax.ws.rs.client.Client;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JAX-RS provider for HTTP client.
 * Needs to be registered in the JAX-RS application.
 * 
 * @author Martynas Jusevičius {@literal <martynas@atomgraph.com>}
 * @see com.sun.jersey.api.client.Client
 * @see javax.ws.rs.core.Context
 */
@Singleton
public class ClientProvider implements Factory<Client>
{
    private static final Logger log = LoggerFactory.getLogger(ClientProvider.class);
    
    private final Client client;
    
    public ClientProvider(final Client client)
    {
        this.client = client;
    }

    @Override
    public Client provide()
    {
        return getClient();
    }

    @Override
    public void dispose(Client t)
    {
        t.close();
    }
    
    public Client getClient()
    {
        return client;
    }
    
}