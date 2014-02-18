/**
 * Copyright 2013 DuraSpace, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.fcrepo.http.api;

import com.codahale.metrics.annotation.Timed;
import org.fcrepo.http.api.versioning.VersionAwareHttpGraphSubjects;
import org.fcrepo.http.commons.AbstractResource;
import org.fcrepo.http.commons.api.rdf.HttpGraphSubjects;
import org.fcrepo.http.commons.responses.HtmlTemplate;
import org.fcrepo.http.commons.session.InjectedSession;
import org.fcrepo.http.commons.session.SessionFactory;
import org.fcrepo.kernel.Datastream;
import org.fcrepo.kernel.FedoraResource;
import org.fcrepo.kernel.FedoraResourceImpl;
import org.fcrepo.kernel.utils.iterators.RdfStream;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.ws.rs.GET;
import javax.ws.rs.HeaderParam;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.PathSegment;
import javax.ws.rs.core.Request;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.UriInfo;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

import static javax.ws.rs.core.MediaType.APPLICATION_XHTML_XML;
import static javax.ws.rs.core.MediaType.APPLICATION_XML;
import static javax.ws.rs.core.MediaType.TEXT_HTML;
import static javax.ws.rs.core.MediaType.TEXT_PLAIN;
import static javax.ws.rs.core.Response.Status.NOT_FOUND;
import static javax.ws.rs.core.Response.noContent;
import static javax.ws.rs.core.Response.status;
import static org.fcrepo.http.commons.domain.RDFMediaType.N3;
import static org.fcrepo.http.commons.domain.RDFMediaType.N3_ALT2;
import static org.fcrepo.http.commons.domain.RDFMediaType.NTRIPLES;
import static org.fcrepo.http.commons.domain.RDFMediaType.RDF_XML;
import static org.fcrepo.http.commons.domain.RDFMediaType.TURTLE;
import static org.fcrepo.http.commons.domain.RDFMediaType.TURTLE_X;
import static org.fcrepo.jcr.FedoraJcrTypes.FCR_CONTENT;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * Endpoint for managing versions of nodes
 */
@Component
@Scope("prototype")
@Path("/{path: .*}/fcr:versions")
public class FedoraVersions extends AbstractResource {

    @InjectedSession
    protected Session session;

    @Autowired
    private SessionFactory sessionFactory = null;

    private static final Logger LOGGER = getLogger(FedoraVersions.class);

    /**
     * Get the list of versions for the object
     *
     * @param pathList
     * @param request
     * @param uriInfo
     * @return
     * @throws RepositoryException
     */
    @GET
    @HtmlTemplate(value = "fcr:versions")
    @Produces({TURTLE, N3, N3_ALT2, RDF_XML, NTRIPLES, APPLICATION_XML, TEXT_PLAIN, TURTLE_X,
                      TEXT_HTML, APPLICATION_XHTML_XML})
    public RdfStream getVersionList(@PathParam("path")
            final List<PathSegment> pathList,
            @Context
            final Request request,
            @Context
            final UriInfo uriInfo) throws RepositoryException {
        final String path = toPath(pathList);

        LOGGER.trace("Getting versions list for: {}", path);

        final FedoraResource resource = nodeService.getObject(session, path);

        return resource.getVersionTriples(nodeTranslator()).session(session).topic(
                nodeTranslator().getGraphSubject(resource.getNode()).asNode());
    }

    /**
     * Create a new version checkpoint and tag it with the given label.  If
     * that label already describes another version it will silently be
     * reassigned to describe this version.
     *
     * @param pathList
     * @param versionPath
     * @return
     * @throws RepositoryException
     */
    @POST
    @Path("/{versionPath:.+}")
    public Response addVersion(@PathParam("path")
            final List<PathSegment> pathList,
            @PathParam("versionPath")
            final String versionPath) throws RepositoryException {
        return addVersion(toPath(pathList), versionPath);
    }

    /**
     * Create a new version checkpoint with no label.
     */
    @POST
    public Response addVersion(@PathParam("path")
            final List<PathSegment> pathList) throws RepositoryException {
        return addVersion(toPath(pathList), null);
    }

    private Response addVersion(final String path, final String label) throws RepositoryException {
        try {
            final FedoraResource resource =
                    nodeService.getObject(session, path);
            versionService.createVersion(session.getWorkspace(),
                    Collections.singleton(path));
            if (label != null) {
                resource.addVersionLabel(label);
            }
            return noContent().build();
        } finally {
            session.logout();
        }
    }

    /**
     * Retrieve a version of an object.  The path structure is as follows
     * (though these URLs are returned from getVersionList and need not be
     * constructed manually):
     * /versionable-node/fcr:versions/uuid-of-the-frozen-node/path/to/any/copied/unversionable/nodes
     * /versionable-node/fcr:versions/label/path/to/any/copied/unversionable/nodes
     * @param pathList
     * @param versionPath the tagged label of a version or the UUID of the
     *                     frozenNode that stores that version.
     * @param uriInfo
     * @return
     * @throws RepositoryException
     */
    @Path("/{versionPath:.+}")
    @GET
    @Produces({TURTLE, N3, N3_ALT2, RDF_XML, NTRIPLES, APPLICATION_XML, TEXT_PLAIN, TURTLE_X,
                      TEXT_HTML, APPLICATION_XHTML_XML})
    public RdfStream getVersion(@PathParam("path")
            final List<PathSegment> pathList,
            @PathParam("versionPath")
            final String versionPath,
            @Context
            final Request request,
            @Context
            final UriInfo uriInfo) throws RepositoryException {
        final String path = toPath(pathList);
        LOGGER.trace("Getting version profile for: {} at version: {}", path,
                versionPath);
        final Node node = nodeTranslator().getNodeFromGraphSubjectForVersionNode(uriInfo.getRequestUri().toString());
        if (node == null) {
            throw new WebApplicationException(status(NOT_FOUND).build());
        } else {
            final FedoraResource resource = new FedoraResourceImpl(node);
            return resource.getTriples(nodeTranslator()).session(session).topic(
                    nodeTranslator().getGraphSubject(resource.getNode()).asNode());
        }
    }

    /**
     * Get the binary content of a historic version of a datastream.
     * @see FedoraContent#getContent
     * @param pathList
     * @return Binary blob
     * @throws RepositoryException
     */
    @Path("/{versionPath:.+}/fcr:content")
    @GET
    @Timed
    public Response getHistoricContent(@PathParam("path")
                                       final List<PathSegment> pathList, @HeaderParam("Range")
                                       final String rangeValue, @Context
                                       final Request request) throws RepositoryException, IOException {
        try {
            final String path = toPath(pathList);
            LOGGER.info("Attempting get of {}.", path);
            final Node frozenNode = nodeTranslator().getNodeFromGraphSubjectForVersionNode(
                    uriInfo.getRequestUri().getPath().replace(FCR_CONTENT, ""));
            final Datastream ds =
                    datastreamService.asDatastream(frozenNode);
            final HttpGraphSubjects subjects =
                    new HttpGraphSubjects(session, FedoraNodes.class,
                            uriInfo);
            return FedoraContent.getDatastreamContentResponse(ds, rangeValue,
                    request, subjects);

        } finally {
            session.logout();
        }
    }

    /**
     * A translator suitable for subjects that represent nodes.
     */
    protected VersionAwareHttpGraphSubjects nodeTranslator() throws RepositoryException {
        return new VersionAwareHttpGraphSubjects(session,
                sessionFactory.getInternalSession(), FedoraNodes.class,
                uriInfo);
    }

}
