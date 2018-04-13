/*
 * Licensed to DuraSpace under one or more contributor license agreements.
 * See the NOTICE file distributed with this work for additional information
 * regarding copyright ownership.
 *
 * DuraSpace licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except in
 * compliance with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.fcrepo.kernel.modeshape.services;

import static java.util.Arrays.asList;
import static java.util.Arrays.stream;
import static org.apache.jena.graph.NodeFactory.createURI;
import static org.fcrepo.kernel.api.FedoraTypes.CONTENT_DIGEST;
import static org.fcrepo.kernel.api.FedoraTypes.FEDORA_BINARY;
import static org.fcrepo.kernel.api.FedoraTypes.FEDORA_NON_RDF_SOURCE_DESCRIPTION;
import static org.fcrepo.kernel.api.FedoraTypes.FEDORA_RESOURCE;
import static org.fcrepo.kernel.api.FedoraTypes.MEMENTO;
import static org.fcrepo.kernel.api.FedoraTypes.MEMENTO_DATETIME;
import static org.fcrepo.kernel.api.FedoraTypes.MEMENTO_ORIGINAL;
import static org.fcrepo.kernel.api.RdfLexicon.NT_LEAF_NODE;
import static org.fcrepo.kernel.api.RdfLexicon.NT_VERSION_FILE;
import static org.fcrepo.kernel.api.RequiredRdfContext.EMBED_RESOURCES;
import static org.fcrepo.kernel.api.RequiredRdfContext.LDP_CONTAINMENT;
import static org.fcrepo.kernel.api.RequiredRdfContext.LDP_MEMBERSHIP;
import static org.fcrepo.kernel.api.RequiredRdfContext.PROPERTIES;
import static org.fcrepo.kernel.api.RequiredRdfContext.SERVER_MANAGED;
import static org.fcrepo.kernel.modeshape.FedoraResourceImpl.LDPCV_BINARY_TIME_MAP;
import static org.fcrepo.kernel.modeshape.FedoraResourceImpl.LDPCV_TIME_MAP;
import static org.fcrepo.kernel.modeshape.FedoraSessionImpl.getJcrSession;
import static org.fcrepo.kernel.modeshape.rdf.impl.RequiredPropertiesUtil.assertRequiredBinaryTriples;
import static org.fcrepo.kernel.modeshape.rdf.impl.RequiredPropertiesUtil.assertRequiredContainerTriples;
import static org.fcrepo.kernel.modeshape.utils.FedoraTypesUtils.getJcrNode;
import static org.fcrepo.kernel.api.RdfLexicon.HAS_FIXITY_SERVICE;
import static org.modeshape.jcr.api.JcrConstants.JCR_CONTENT;
import static org.modeshape.jcr.api.JcrConstants.NT_FOLDER;
import static org.modeshape.jcr.api.JcrConstants.NT_RESOURCE;
import static org.slf4j.LoggerFactory.getLogger;

import java.io.InputStream;
import java.net.URI;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Calendar;
import java.util.Collection;
import java.util.GregorianCalendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.inject.Inject;
import javax.jcr.ItemExistsException;
import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import org.apache.jena.graph.Triple;
import org.apache.jena.rdf.model.Model;
import org.apache.jena.rdf.model.ModelFactory;
import org.apache.jena.rdf.model.Resource;
import org.apache.jena.riot.Lang;
import org.fcrepo.kernel.api.FedoraSession;
import org.fcrepo.kernel.api.RdfStream;
import org.fcrepo.kernel.api.TripleCategory;
import org.fcrepo.kernel.api.exception.InvalidChecksumException;
import org.fcrepo.kernel.api.exception.RepositoryRuntimeException;
import org.fcrepo.kernel.api.identifiers.IdentifierConverter;
import org.fcrepo.kernel.api.models.Container;
import org.fcrepo.kernel.api.models.FedoraBinary;
import org.fcrepo.kernel.api.models.FedoraResource;
import org.fcrepo.kernel.api.models.NonRdfSourceDescription;
import org.fcrepo.kernel.api.rdf.DefaultRdfStream;
import org.fcrepo.kernel.api.services.BinaryService;
import org.fcrepo.kernel.api.services.NodeService;
import org.fcrepo.kernel.api.services.VersionService;
import org.fcrepo.kernel.api.services.policy.StoragePolicyDecisionPoint;
import org.fcrepo.kernel.modeshape.ContainerImpl;
import org.fcrepo.kernel.modeshape.FedoraBinaryImpl;
import org.fcrepo.kernel.modeshape.NonRdfSourceDescriptionImpl;
import org.fcrepo.kernel.modeshape.utils.iterators.RelaxedRdfAdder;
import org.slf4j.Logger;
import org.springframework.stereotype.Component;;

/**
 * This service exposes management of node versioning for resources and binaries.
 *
 * @author Mike Durbin
 * @author bbpennel
 */

@Component
public class VersionServiceImpl extends AbstractService implements VersionService {

    private static final Logger LOGGER = getLogger(VersionService.class);

    private static final DateTimeFormatter MEMENTO_DATETIME_ID_FORMATTER =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(ZoneId.of("GMT"));

    private static final Set<TripleCategory> VERSION_TRIPLES = new HashSet<>(asList(
            PROPERTIES, EMBED_RESOURCES, SERVER_MANAGED, LDP_MEMBERSHIP, LDP_CONTAINMENT));

    /**
     * The bitstream service
     */
    @Inject
    protected BinaryService binaryService;

    @Inject
    protected NodeService nodeService;

    @Override
    public FedoraResource createVersion(final FedoraSession session, final FedoraResource resource,
            final IdentifierConverter<Resource, FedoraResource> idTranslator, final Instant dateTime) {
        return createVersion(session, resource, idTranslator, dateTime, null, null);
    }

    @Override
    public FedoraResource createVersion(final FedoraSession session,
            final FedoraResource resource,
            final IdentifierConverter<Resource, FedoraResource> idTranslator,
            final Instant dateTime,
            final InputStream rdfInputStream,
            final Lang rdfFormat) {

        final String mementoPath = makeMementoPath(resource, dateTime);

        assertMementoDoesNotExist(session, mementoPath);

        // Construct an unpopulated resource of the appropriate type for new memento
        final FedoraResource mementoResource;
        if (resource instanceof Container) {
            mementoResource = createContainer(session, mementoPath);
        } else {
            mementoResource = createNonRdfSourceMemento(session, mementoPath);
        }

        decorateWithMementoProperties(session, mementoPath, dateTime, resource);

        final String mementoUri = getUri(mementoResource, idTranslator);

        // uri of the original resource for remapping triples to memento uri
        final String resourceUri;

        final RdfStream mementoRdfStream;
        if (rdfInputStream == null) {
            // With no rdf body provided, create version from current resource state.
            final FedoraResource described = resource.getDescribedResource();
            mementoRdfStream = described.getTriples(idTranslator, VERSION_TRIPLES);
            resourceUri = getUri(described, idTranslator);
        } else {
            resourceUri = getUri(resource.getDescribedResource(), idTranslator);

            final Model inputModel = ModelFactory.createDefaultModel();
            inputModel.read(rdfInputStream, mementoUri, rdfFormat.getName());

            // Validate server managed triples are provided
            if (mementoResource instanceof NonRdfSourceDescription) {
                assertRequiredBinaryTriples(inputModel);
            } else {
                assertRequiredContainerTriples(inputModel);
            }

            mementoRdfStream = DefaultRdfStream.fromModel(createURI(mementoUri), inputModel);
        }

        // Remap the subject of triples to the memento's uri
        final RdfStream mappedStream = remapRdfSubjects(resourceUri, mementoUri, mementoRdfStream);

        final RdfStream removeGenerated = new DefaultRdfStream(createURI(mementoUri), mappedStream
                .filter(t -> !HAS_FIXITY_SERVICE.getURI().equals(t.getPredicate().getURI())));
        // Add triples from source
        final Session jcrSession = getJcrSession(session);
        new RelaxedRdfAdder(idTranslator, jcrSession, removeGenerated, session.getNamespaces()).consume();

        return mementoResource;
    }

    private Container createContainer(final FedoraSession session, final String path) {
        try {
            final Node node = findOrCreateNode(session, path, NT_FOLDER);

            if (node.canAddMixin(FEDORA_RESOURCE)) {
                node.addMixin(FEDORA_RESOURCE);
            }

            return new ContainerImpl(node);
        } catch (final RepositoryException e) {
            throw new RepositoryRuntimeException(e);
        }
    }

    /*
     * Creates memento node for non-rdf source using leaf node type which does not require children or jcr:content
     */
    private NonRdfSourceDescription createNonRdfSourceMemento(final FedoraSession session, final String path) {
        try {
            // Using nt:leafNode to avoid requiring jcr:content or allowing subfolders in memento
            final Node dsNode = findOrCreateNode(session, path, NT_LEAF_NODE);

            if (dsNode.canAddMixin(FEDORA_RESOURCE)) {
                dsNode.addMixin(FEDORA_RESOURCE);
            }
            //
            // if (dsNode.canAddMixin(FEDORA_NON_RDF_SOURCE_DESCRIPTION)) {
            // dsNode.addMixin(FEDORA_NON_RDF_SOURCE_DESCRIPTION);
            // }

            if (dsNode.canAddMixin(FEDORA_BINARY)) {
                dsNode.addMixin(FEDORA_BINARY);
            }

            return new NonRdfSourceDescriptionImpl(dsNode);
        } catch (final RepositoryException e) {
            throw new RepositoryRuntimeException(e);
        }
    }

    /**
     * Remaps subject uris from resourceUri to mementoUri.
     *
     * @param resourceUri subject to remap from
     * @param mementoUri subject to remap to
     * @param rdfStream stream to map
     * @return remapped stream
     */
    private RdfStream remapRdfSubjects(final String resourceUri, final String mementoUri, final RdfStream rdfStream) {
        final org.apache.jena.graph.Node mementoNode = createURI(mementoUri);
        final Stream<Triple> updatedSubjectStream = rdfStream.map(t -> {
            final org.apache.jena.graph.Node subject;
            if (t.getSubject().getURI().equals(resourceUri)) {
                subject = mementoNode;
            } else {
                subject = t.getSubject();
            }
            return new Triple(subject, t.getPredicate(), t.getObject());
        });
        return new DefaultRdfStream(mementoNode, updatedSubjectStream);
    }

    @Override
    public FedoraBinary createBinaryVersion(final FedoraSession session,
            final FedoraBinary resource,
            final Instant dateTime,
            final StoragePolicyDecisionPoint storagePolicyDecisionPoint) throws InvalidChecksumException {
        return createBinaryVersion(session, resource, dateTime, null, null, null, null, storagePolicyDecisionPoint);
    }

    @Override
    public FedoraBinary createBinaryVersion(final FedoraSession session,
            final FedoraBinary resource,
            final Instant dateTime,
            final InputStream contentStream,
            final String filename,
            final String mimetype,
            final Collection<URI> checksums,
            final StoragePolicyDecisionPoint storagePolicyDecisionPoint) throws InvalidChecksumException {

        final String mementoPath = makeMementoPath(resource, dateTime);

        assertMementoDoesNotExist(session, mementoPath);

        LOGGER.debug("Creating memento {} for resource {} using existing state", mementoPath, resource.getPath());

        final FedoraBinary memento = createBinary(session, mementoPath);

        if (contentStream == null || mimetype == null) {
            // Creating memento from existing resource
            populateBinaryMementoFromExisting(resource, memento);
        } else {
            memento.setContent(contentStream, mimetype, checksums, filename, null);
        }

        decorateWithMementoProperties(session, mementoPath, dateTime, resource);

        return memento;
    }

    private void populateBinaryMementoFromExisting(final FedoraBinary resource, final FedoraBinary memento)
            throws InvalidChecksumException {

        final Node contentNode = getJcrNode(resource);
        List<URI> checksums = null;
        // Retrieve all existing digests from the original
        try {
            if (contentNode.hasProperty(CONTENT_DIGEST)) {
                final Property digestProperty = contentNode.getProperty(CONTENT_DIGEST);
                checksums = stream(digestProperty.getValues())
                        .map(d -> {
                            try {
                                return URI.create(d.getString());
                            } catch (final RepositoryException e) {
                                throw new RepositoryRuntimeException(e);
                            }
                        }).collect(Collectors.toList());
            }

            memento.setContent(resource.getContent(), resource.getMimeType(), checksums,
                    resource.getFilename(), null);
        } catch (final RepositoryException e) {
            throw new RepositoryRuntimeException(e);
        }
    }

    private FedoraBinary createBinary(final FedoraSession session, final String path) {
        try {
            final Node dsNode = findOrCreateNode(session, path, NT_VERSION_FILE);

            if (dsNode.canAddMixin(FEDORA_RESOURCE)) {
                dsNode.addMixin(FEDORA_RESOURCE);
            }

            if (dsNode.canAddMixin(FEDORA_NON_RDF_SOURCE_DESCRIPTION)) {
                dsNode.addMixin(FEDORA_NON_RDF_SOURCE_DESCRIPTION);
            }

            final Node contentNode = jcrTools.findOrCreateChild(dsNode, JCR_CONTENT, NT_RESOURCE);

            if (contentNode.canAddMixin(FEDORA_BINARY)) {
                contentNode.addMixin(FEDORA_BINARY);
            }

            return new FedoraBinaryImpl(contentNode);
        } catch (final RepositoryException e) {
            throw new RepositoryRuntimeException(e);
        }
    }

    /**
     * Produces the node path where the memento for the given resource should be stored. Path depends on the type of
     * resource provided (binary timemaps have a distinct path) and memento datetime.
     *
     * @param resource resource for which a memento path will be generated
     * @param datetime memento datetime.
     * @return
     */
    private String makeMementoPath(final FedoraResource resource, final Instant datetime) {
        final String ldpcvName = resource instanceof FedoraBinary ? LDPCV_BINARY_TIME_MAP : LDPCV_TIME_MAP;
        return resource.getPath() + "/" + ldpcvName + "/" + MEMENTO_DATETIME_ID_FORMATTER.format(datetime);
    }

    protected String getUri(final FedoraResource resource,
            final IdentifierConverter<Resource, FedoraResource> idTranslator) {
        if (idTranslator == null) {
            return resource.getPath();
        }
        return idTranslator.reverse().convert(resource).getURI();
    }

    /*
     * Add required memento properties and types to resource
     */
    protected void decorateWithMementoProperties(final FedoraSession session, final String mementoPath,
            final Instant dateTime, final FedoraResource originalResc) {
        try {
            final Node mementoNode = findNode(session, mementoPath);
            if (mementoNode.canAddMixin(MEMENTO)) {
                mementoNode.addMixin(MEMENTO);
            }
            final Calendar mementoDatetime = GregorianCalendar.from(
                    ZonedDateTime.ofInstant(dateTime, ZoneId.of("UTC")));
            mementoNode.setProperty(MEMENTO_DATETIME, mementoDatetime);
            mementoNode.setProperty(MEMENTO_ORIGINAL, getJcrNode(originalResc));
        } catch (final RepositoryException e) {
            throw new RepositoryRuntimeException(e);
        }
    }

    protected void assertMementoDoesNotExist(final FedoraSession session, final String mementoPath) {
        if (exists(session, mementoPath)) {
            throw new RepositoryRuntimeException(new ItemExistsException(
                    "Memento " + mementoPath + " already exists"));
        }
    }
}
