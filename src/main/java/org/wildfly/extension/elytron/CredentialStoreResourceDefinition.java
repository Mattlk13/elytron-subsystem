/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2015 Red Hat, Inc., and individual contributors
 * as indicated by the @author tags.
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

package org.wildfly.extension.elytron;

import static org.jboss.as.controller.security.CredentialReference.credentialReferencePartAsStringIfDefined;
import static org.wildfly.extension.elytron.Capabilities.CREDENTIAL_STORE_CAPABILITY;
import static org.wildfly.extension.elytron.Capabilities.CREDENTIAL_STORE_RUNTIME_CAPABILITY;
import static org.wildfly.extension.elytron.Capabilities.PROVIDERS_CAPABILITY;
import static org.wildfly.extension.elytron.ElytronDefinition.commonDependencies;
import static org.wildfly.extension.elytron.ElytronExtension.asStringIfDefined;
import static org.wildfly.extension.elytron.FileAttributeDefinitions.pathName;
import static org.wildfly.extension.elytron.ProviderLoaderDefinition.PROVIDER_LOADER_SERVICE_UTIL;
import static org.wildfly.extension.elytron.ServiceStateDefinition.STATE;
import static org.wildfly.extension.elytron.ServiceStateDefinition.populateResponse;
import static org.wildfly.extension.elytron._private.ElytronSubsystemMessages.ROOT_LOGGER;

import java.io.IOException;
import java.security.Provider;
import java.security.spec.AlgorithmParameterSpec;
import java.util.StringTokenizer;

import org.jboss.as.controller.AbstractAddStepHandler;
import org.jboss.as.controller.AbstractRuntimeOnlyHandler;
import org.jboss.as.controller.AttributeDefinition;
import org.jboss.as.controller.ModelOnlyWriteAttributeHandler;
import org.jboss.as.controller.ObjectTypeAttributeDefinition;
import org.jboss.as.controller.OperationContext;
import org.jboss.as.controller.OperationFailedException;
import org.jboss.as.controller.OperationStepHandler;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.controller.ResourceDefinition;
import org.jboss.as.controller.SimpleAttributeDefinition;
import org.jboss.as.controller.SimpleAttributeDefinitionBuilder;
import org.jboss.as.controller.SimpleOperationDefinition;
import org.jboss.as.controller.SimpleOperationDefinitionBuilder;
import org.jboss.as.controller.SimpleResourceDefinition;
import org.jboss.as.controller.capability.RuntimeCapability;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.controller.descriptions.StandardResourceDescriptionResolver;
import org.jboss.as.controller.registry.AttributeAccess;
import org.jboss.as.controller.registry.ManagementResourceRegistration;
import org.jboss.as.controller.registry.OperationEntry;
import org.jboss.as.controller.registry.Resource;
import org.jboss.as.controller.security.CredentialReference;
import org.jboss.as.controller.services.path.PathManager;
import org.jboss.as.controller.services.path.PathManagerService;
import org.jboss.dmr.ModelNode;
import org.jboss.dmr.ModelType;
import org.jboss.msc.service.ServiceBuilder;
import org.jboss.msc.service.ServiceController;
import org.jboss.msc.service.ServiceController.Mode;
import org.jboss.msc.service.ServiceController.State;
import org.jboss.msc.service.ServiceName;
import org.jboss.msc.service.ServiceTarget;
import org.jboss.msc.service.StartException;
import org.jboss.msc.value.InjectedValue;
import org.wildfly.common.function.ExceptionSupplier;
import org.wildfly.security.auth.SupportLevel;
import org.wildfly.security.credential.Credential;
import org.wildfly.security.credential.PasswordCredential;
import org.wildfly.security.credential.source.CommandCredentialSource;
import org.wildfly.security.credential.source.CredentialSource;
import org.wildfly.security.credential.source.CredentialStoreCredentialSource;
import org.wildfly.security.credential.store.CredentialStore;
import org.wildfly.security.credential.store.CredentialStoreException;
import org.wildfly.security.password.interfaces.ClearPassword;

/**
 * A {@link ResourceDefinition} for a CredentialStore.
 *
 * @author <a href="mailto:pskopek@redhat.com">Peter Skopek</a>
 */
final class CredentialStoreResourceDefinition extends SimpleResourceDefinition {

    static final ServiceUtil<CredentialStore> CREDENTIAL_STORE_UTIL = ServiceUtil.newInstance(CREDENTIAL_STORE_RUNTIME_CAPABILITY, ElytronDescriptionConstants.CREDENTIAL_STORE, CredentialStore.class);

    static final SimpleAttributeDefinition URI = new SimpleAttributeDefinitionBuilder(ElytronDescriptionConstants.URI, ModelType.STRING, false)
            .setAttributeGroup(ElytronDescriptionConstants.IMPLEMENTATION)
            .setAllowExpression(true)
            .setMinSize(1)
            .setFlags(AttributeAccess.Flag.RESTART_RESOURCE_SERVICES)
            .build();

    static final ObjectTypeAttributeDefinition CREDENTIAL_REFERENCE =
            CredentialReference.getAttributeBuilder(CredentialReference.CREDENTIAL_REFERENCE, CredentialReference.CREDENTIAL_REFERENCE, false)
                    .setCapabilityReference(CREDENTIAL_STORE_CAPABILITY)
                    .build();

    static final SimpleAttributeDefinition TYPE = new SimpleAttributeDefinitionBuilder(ElytronDescriptionConstants.TYPE, ModelType.STRING, true)
            .setAttributeGroup(ElytronDescriptionConstants.IMPLEMENTATION)
            .setAllowExpression(true)
            .setMinSize(1)
            .setFlags(AttributeAccess.Flag.RESTART_RESOURCE_SERVICES)
            .build();

    static final SimpleAttributeDefinition PROVIDER = new SimpleAttributeDefinitionBuilder(ElytronDescriptionConstants.PROVIDER, ModelType.STRING, true)
            .setAttributeGroup(ElytronDescriptionConstants.IMPLEMENTATION)
            .setAllowExpression(true)
            .setMinSize(1)
            .setFlags(AttributeAccess.Flag.RESTART_RESOURCE_SERVICES)
            .build();

    static final SimpleAttributeDefinition PROVIDER_LOADER = new SimpleAttributeDefinitionBuilder(ElytronDescriptionConstants.PROVIDER_LOADER, ModelType.STRING, true)
            .setAttributeGroup(ElytronDescriptionConstants.IMPLEMENTATION)
            .setAllowExpression(false)
            .setMinSize(1)
            .setFlags(AttributeAccess.Flag.RESTART_RESOURCE_SERVICES)
            .setCapabilityReference(PROVIDERS_CAPABILITY, CREDENTIAL_STORE_CAPABILITY, true)
            .build();

    static final SimpleAttributeDefinition RELATIVE_TO = new SimpleAttributeDefinitionBuilder(ElytronDescriptionConstants.RELATIVE_TO, ModelType.STRING, true)
            .setAllowExpression(false)
            .setMinSize(1)
            .setAttributeGroup(ElytronDescriptionConstants.FILE)
            .setFlags(AttributeAccess.Flag.RESTART_RESOURCE_SERVICES)
            .build();

    // Resource Resolver
    static final StandardResourceDescriptionResolver RESOURCE_RESOLVER = ElytronExtension.getResourceDescriptionResolver(ElytronDescriptionConstants.CREDENTIAL_STORE);

    // Operations
    static final SimpleOperationDefinition RELOAD = new SimpleOperationDefinitionBuilder(ElytronDescriptionConstants.RELOAD, RESOURCE_RESOLVER)
            .build();

    private static final AttributeDefinition[] CONFIG_ATTRIBUTES = new AttributeDefinition[] {URI, CREDENTIAL_REFERENCE, TYPE, PROVIDER, PROVIDER_LOADER, RELATIVE_TO};

    private static final CredentialStoreAddHandler ADD = new CredentialStoreAddHandler();
    private static final OperationStepHandler REMOVE = new TrivialCapabilityServiceRemoveHandler(ADD, CREDENTIAL_STORE_RUNTIME_CAPABILITY);
    private static final WriteAttributeHandler WRITE = new WriteAttributeHandler();

    CredentialStoreResourceDefinition() {
        super(new Parameters(PathElement.pathElement(ElytronDescriptionConstants.CREDENTIAL_STORE), RESOURCE_RESOLVER)
                .setAddHandler(ADD)
                .setRemoveHandler(REMOVE)
                .setAddRestartLevel(OperationEntry.Flag.RESTART_NONE)
                .setRemoveRestartLevel(OperationEntry.Flag.RESTART_NONE));
    }

    @Override
    public void registerAttributes(ManagementResourceRegistration resourceRegistration) {
        for (AttributeDefinition current : CONFIG_ATTRIBUTES) {
            resourceRegistration.registerReadWriteAttribute(current, null, WRITE);
        }

        resourceRegistration.registerReadOnlyAttribute(STATE, new AbstractRuntimeOnlyHandler() {

            @Override
            protected void executeRuntimeStep(OperationContext context, ModelNode operation) throws OperationFailedException {
                ServiceName credentialStoreClientServiceName = CREDENTIAL_STORE_UTIL.serviceName(operation);
                ServiceController<?> serviceController = context.getServiceRegistry(false).getRequiredService(credentialStoreClientServiceName);

                populateResponse(context.getResult(), serviceController);
            }

        });

    }

    @Override
    public void registerOperations(ManagementResourceRegistration resourceRegistration) {
        super.registerOperations(resourceRegistration);
        resourceRegistration.registerOperationHandler(RELOAD, CredentialStoreHandler.INSTANCE);
    }

    @Override
    public void registerChildren(ManagementResourceRegistration resourceRegistration) {
        resourceRegistration.registerSubModel(new CredentialStoreAliasDefinition());
    }

    private static class CredentialStoreAddHandler extends AbstractAddStepHandler {

        private CredentialStoreAddHandler() {
            super(CREDENTIAL_STORE_RUNTIME_CAPABILITY, CONFIG_ATTRIBUTES);
        }

        @Override
        protected void performRuntime(OperationContext context, ModelNode operation, Resource resource) throws OperationFailedException {

            ModelNode model = resource.getModel();
            String uri = asStringIfDefined(context, URI, model);
            String type = asStringIfDefined(context, TYPE, model);
            String providerLoader = asStringIfDefined(context, PROVIDER_LOADER, model);
            String provider = asStringIfDefined(context, PROVIDER, model);
            String name = credentialStoreName(operation);
            String relativeTo = asStringIfDefined(context, RELATIVE_TO, model);
            ServiceTarget serviceTarget = context.getServiceTarget();

            // ----------- credential store service ----------------
            final CredentialStoreService csService;
            try {
                csService = CredentialStoreService.createCredentialStoreService(name, uri, type, provider, relativeTo, providerLoader);
            } catch (CredentialStoreException e) {
                throw new OperationFailedException(e);
            }
            ServiceName credentialStoreServiceName = CREDENTIAL_STORE_UTIL.serviceName(operation);
            ServiceBuilder<CredentialStore> credentialStoreServiceBuilder = serviceTarget.addService(credentialStoreServiceName, csService)
                    .setInitialMode(Mode.ACTIVE);

            if (relativeTo != null) {
                credentialStoreServiceBuilder.addDependency(PathManagerService.SERVICE_NAME, PathManager.class, csService.getPathManagerInjector());
                credentialStoreServiceBuilder.addDependency(pathName(relativeTo));
            }
            if (providerLoader != null) {
                String providersCapabilityName = RuntimeCapability.buildDynamicCapabilityName(PROVIDERS_CAPABILITY, providerLoader);
                ServiceName providerLoaderServiceName = context.getCapabilityServiceName(providersCapabilityName, Provider[].class);
                PROVIDER_LOADER_SERVICE_UTIL.addInjection(credentialStoreServiceBuilder, csService.getProvidersInjector(), providerLoaderServiceName);
            }

            csService.getCredentialSourceSupplierInjector()
                    .inject(CredentialStoreResourceDefinition.createCredentialSource(context, model, credentialStoreServiceBuilder));

            commonDependencies(credentialStoreServiceBuilder);
            ServiceController<CredentialStore> credentialStoreServiceController = credentialStoreServiceBuilder.install();
            ((CredentialStoreResource)resource).setCredentialStoreServiceController(credentialStoreServiceController);

        }

        @Override
        protected Resource createResource(OperationContext context) {
            CredentialStoreResource resource = new CredentialStoreResource(Resource.Factory.create());
            context.addResource(PathAddress.EMPTY_ADDRESS, resource);

            return resource;
        }

    }

    private static class WriteAttributeHandler extends ModelOnlyWriteAttributeHandler {

        WriteAttributeHandler() {
            super(CONFIG_ATTRIBUTES);
        }

    }

    /*
     * Runtime Attribute and Operation Handlers
     */

    abstract static class CredentialStoreRuntimeOnlyHandler extends AbstractRuntimeOnlyHandler {

        private final boolean serviceMustBeUp;
        private final boolean writeAccess;

        CredentialStoreRuntimeOnlyHandler(final boolean serviceMustBeUp, final boolean writeAccess) {
            this.serviceMustBeUp = serviceMustBeUp;
            this.writeAccess = writeAccess;
        }

        CredentialStoreRuntimeOnlyHandler(final boolean serviceMustBeUp) {
            this(serviceMustBeUp, false);
        }


        @Override
        protected void executeRuntimeStep(OperationContext context, ModelNode operation) throws OperationFailedException {
            ServiceName credentialStoreServiceName = CREDENTIAL_STORE_UTIL.serviceName(operation);
            ServiceController<?> credentialStoreServiceController = context.getServiceRegistry(writeAccess).getRequiredService(credentialStoreServiceName);
            State serviceState;
            if ((serviceState = credentialStoreServiceController.getState()) != State.UP) {
                if (serviceMustBeUp) {
                    throw ROOT_LOGGER.requiredServiceNotUp(credentialStoreServiceName, serviceState);
                }
                return;
            }
            CredentialStoreService service = (CredentialStoreService) credentialStoreServiceController.getService();
            performRuntime(context.getResult(), context, operation, service);
        }

        protected abstract void performRuntime(ModelNode result, OperationContext context, ModelNode operation,  CredentialStoreService credentialStoreService) throws OperationFailedException ;

    }


    private static class CredentialStoreHandler extends CredentialStoreRuntimeOnlyHandler {

        private static final CredentialStoreHandler INSTANCE = new CredentialStoreHandler();

        private CredentialStoreHandler() {
            super(true, true);
        }

        @Override
        protected void performRuntime(ModelNode result, OperationContext context, ModelNode operation, CredentialStoreService credentialStoreService) throws OperationFailedException {

            String operationName = operation.require(ModelDescriptionConstants.OP).asString();
            switch (operationName) {
                case ElytronDescriptionConstants.RELOAD:
                    try {
                        credentialStoreService.stop(null);
                        credentialStoreService.start(null);
                    } catch (StartException e) {
                        throw new OperationFailedException(e);
                    }

                    break;
                default:
                    throw ROOT_LOGGER.invalidOperationName(operationName, ElytronDescriptionConstants.LOAD);
            }
        }
    }

    static String credentialStoreName(ModelNode operation) {
        String credentialStoreName = null;
        PathAddress pa = PathAddress.pathAddress(operation.require(ModelDescriptionConstants.OP_ADDR));
        for (int i = pa.size() - 1; i > 0; i--) {
            PathElement pe = pa.getElement(i);
            if (ElytronDescriptionConstants.CREDENTIAL_STORE.equals(pe.getKey())) {
                credentialStoreName = pe.getValue();
                break;
            }
        }

        if (credentialStoreName == null) {
            throw ROOT_LOGGER.operationAddressMissingKey(ElytronDescriptionConstants.CREDENTIAL_STORE);
        }

        return credentialStoreName;
    }

    static ExceptionSupplier<CredentialSource, Exception> createCredentialSource(OperationContext context, ModelNode model, ServiceBuilder<?> serviceBuilder) throws OperationFailedException {

        String credentialStoreName = credentialReferencePartAsStringIfDefined(context, CREDENTIAL_REFERENCE, model, CredentialReference.STORE);
        String credentialAlias = credentialReferencePartAsStringIfDefined(context, CREDENTIAL_REFERENCE, model, CredentialReference.ALIAS);
        String credentialType = credentialReferencePartAsStringIfDefined(context, CREDENTIAL_REFERENCE, model, CredentialReference.TYPE);
        String secret = credentialReferencePartAsStringIfDefined(context, CREDENTIAL_REFERENCE, model, CredentialReference.CLEAR_TEXT);

        final InjectedValue<CredentialStore> credentialStoreInjectedValue = new InjectedValue<>();
        if (credentialAlias != null) {
            // use credential store service
            String credentialStoreCapabilityName = RuntimeCapability.buildDynamicCapabilityName(CREDENTIAL_STORE_CAPABILITY, credentialStoreName);
            ServiceName credentialStoreServiceName = context.getCapabilityServiceName(credentialStoreCapabilityName, CredentialStore.class);
            serviceBuilder.addDependency(ServiceBuilder.DependencyType.REQUIRED, credentialStoreServiceName, CredentialStore.class, credentialStoreInjectedValue);
        }

        return new ExceptionSupplier<CredentialSource, Exception>() {

            private String[] parseCommand(String command) {
                // comma can be back slashed
                final String[] parsedCommand = command.split("(?<!\\\\),");
                for (int k = 0; k < parsedCommand.length; k++) {
                    if (parsedCommand[k].indexOf('\\') != -1)
                        parsedCommand[k] = parsedCommand[k].replaceAll("\\\\,", ",");
                }
                return parsedCommand;
            }

            private String stripType(String commandSpec) {
                StringTokenizer tokenizer = new StringTokenizer(commandSpec, "{}");
                tokenizer.nextToken();
                return tokenizer.nextToken();
            }

            /**
             * Gets a Credential Store Supplier.
             *
             * @return a supplier
             */
            @Override
            public CredentialSource get() throws Exception {
                if (credentialAlias != null) {
                    return new CredentialStoreCredentialSource(credentialStoreInjectedValue.getValue(), credentialAlias);
                } else if (credentialType != null && credentialType.equalsIgnoreCase("COMMAND")) {
                    // this is temporary solution until properly moved to WF-CORE
                    CommandCredentialSource.Builder command = CommandCredentialSource.builder();
                    String commandSpec = secret.trim();
                    if (commandSpec.startsWith("{EXT")) {
                        commandSpec = stripType(commandSpec);
                        command.addCommand(commandSpec);
                    } else if (commandSpec.startsWith("{CMD")) {
                        String[] parts = parseCommand(stripType(commandSpec));
                        for(String part: parts) {
                            command.addCommand(part);
                        }
                    }
                    return command.build();
                } else if (secret != null && secret.startsWith("MASK-")) {
                    if (credentialStoreName != null) {
                        return new CredentialStoreCredentialSource(credentialStoreInjectedValue.getValue(), secret.substring("MASK-".length()));
                    }
                    throw ROOT_LOGGER.nameOfCredentialStoreHasToBeSpecified();
                } else {
                    // clear text password
                    return new CredentialSource() {
                        @Override
                        public SupportLevel getCredentialAcquireSupport(Class<? extends Credential> credentialType, String algorithmName, AlgorithmParameterSpec parameterSpec) throws IOException {
                            return credentialType == PasswordCredential.class ? SupportLevel.SUPPORTED : SupportLevel.UNSUPPORTED;
                        }

                        @Override
                        public <C extends Credential> C getCredential(Class<C> credentialType, String algorithmName, AlgorithmParameterSpec parameterSpec) throws IOException {
                            return credentialType.cast(new PasswordCredential(ClearPassword.createRaw(ClearPassword.ALGORITHM_CLEAR, secret.toCharArray())));
                        }
                    };
                }
            }
        };
    }

}
