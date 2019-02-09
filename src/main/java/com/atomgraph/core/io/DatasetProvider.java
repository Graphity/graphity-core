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

package com.atomgraph.core.io;

import com.atomgraph.core.MediaTypes;
import org.apache.jena.query.Dataset;
import org.apache.jena.query.DatasetFactory;
import org.apache.jena.shared.NoReaderForLangException;
import org.apache.jena.shared.NoWriterForLangException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Type;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.MultivaluedMap;
import javax.ws.rs.core.Response;
import javax.ws.rs.ext.MessageBodyReader;
import javax.ws.rs.ext.MessageBodyWriter;
import javax.ws.rs.ext.Provider;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.riot.Lang;
import org.apache.jena.riot.RDFDataMgr;
import org.apache.jena.riot.RDFLanguages;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * JAX-RS provider for reading RDF dataset from request and writing it to response.
 * Needs to be registered in the JAX-RS application.
 * 
 * @author Martynas Jusevičius {@literal <martynas@atomgraph.com>}
 * @see org.apache.jena.query.Dataset
 * @see javax.ws.rs.ext.MessageBodyReader
 * @see javax.ws.rs.ext.MessageBodyWriter
 */

@Provider
public class DatasetProvider implements MessageBodyReader<Dataset>, MessageBodyWriter<Dataset>
{

    private static final Logger log = LoggerFactory.getLogger(DatasetProvider.class);

    public static final String REQUEST_URI_HEADER = "X-Request-URI";

    private final MessageBodyReader<Model> modelReader = new ModelProvider();
    private final MessageBodyWriter<Model> modelWriter = new ModelProvider();
    
    // READER
    
    @Override
    public boolean isReadable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType)
    {
        boolean quadsReadable = type == Dataset.class && MediaTypes.isQuads(mediaType);
        if (quadsReadable) return true;
        
        return getModelReader().isReadable(Model.class, Model.class, annotations, mediaType); // fallback to reading Model
    }

    @Override
    public Dataset readFrom(Class<Dataset> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, String> httpHeaders, InputStream entityStream) throws IOException, WebApplicationException
    {
        if (log.isTraceEnabled()) log.trace("Reading Dataset with HTTP headers: {} MediaType: {}", httpHeaders, mediaType);

        Dataset dataset = DatasetFactory.create();

        MediaType formatType = new MediaType(mediaType.getType(), mediaType.getSubtype()); // discard charset param
        Lang lang = RDFLanguages.contentTypeToLang(formatType.toString());
        if (lang == null)
        {
            Throwable ex = new NoReaderForLangException("Media type not supported");
            if (log.isErrorEnabled()) log.error("MediaType {} not supported by Jena", mediaType);
            throw new WebApplicationException(ex, Response.Status.INTERNAL_SERVER_ERROR);
        }

        String baseURI = null;
        // attempt to retrieve base URI from a special-purpose header (workaround for JAX-RS 1.x limitation)
        if (httpHeaders.containsKey(REQUEST_URI_HEADER)) baseURI = httpHeaders.getFirst(REQUEST_URI_HEADER);
        
        RDFDataMgr.read(dataset, entityStream, baseURI, lang);
        
        return dataset;
    }
    
    // WRITER
    
    @Override
    public boolean isWriteable(Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType)
    {
        boolean quadsWriteable = Dataset.class.isAssignableFrom(type) && MediaTypes.isQuads(mediaType);
        if (quadsWriteable) return true;
        
        return getModelWriter().isWriteable(Model.class, Model.class, annotations, mediaType); // fallback to writing Model
    }

    @Override
    public long getSize(Dataset model, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType)
    {
        return -1;
    }

    @Override
    public void writeTo(Dataset dataset, Class<?> type, Type genericType, Annotation[] annotations, MediaType mediaType, MultivaluedMap<String, Object> httpHeaders, OutputStream entityStream) throws IOException, WebApplicationException
    {
        if (log.isTraceEnabled()) log.trace("Writing Dataset with HTTP headers: {} MediaType: {}", httpHeaders, mediaType);

        MediaType formatType = new MediaType(mediaType.getType(), mediaType.getSubtype()); // discard charset param
        Lang lang = RDFLanguages.contentTypeToLang(formatType.toString());
        if (lang == null)
        {
            Throwable ex = new NoWriterForLangException("Media type not supported");
            if (log.isErrorEnabled()) log.error("MediaType {} not supported by Jena", formatType);
            throw new WebApplicationException(ex, Response.Status.INTERNAL_SERVER_ERROR);
        }
        
        // if we need to provide triples, then we write only the default graph of the dataset
        if (MediaTypes.isTriples(mediaType)) dataset.getDefaultModel().write(entityStream, lang.getName());
        else RDFDataMgr.write(entityStream, dataset, lang);
    }

    public MessageBodyReader<Model> getModelReader()
    {
        return modelReader;
    }
    
    public MessageBodyWriter<Model> getModelWriter()
    {
        return modelWriter;
    }
    
}
