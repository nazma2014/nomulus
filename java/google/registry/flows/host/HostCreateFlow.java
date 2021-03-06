// Copyright 2016 The Nomulus Authors. All Rights Reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package google.registry.flows.host;

import static google.registry.flows.ResourceFlowUtils.verifyResourceDoesNotExist;
import static google.registry.flows.host.HostFlowUtils.lookupSuperordinateDomain;
import static google.registry.flows.host.HostFlowUtils.validateHostName;
import static google.registry.flows.host.HostFlowUtils.verifyDomainIsSameRegistrar;
import static google.registry.model.EppResourceUtils.createContactHostRoid;
import static google.registry.model.eppoutput.Result.Code.SUCCESS;
import static google.registry.model.ofy.ObjectifyService.ofy;
import static google.registry.util.CollectionUtils.isNullOrEmpty;
import static google.registry.util.CollectionUtils.union;

import com.google.common.base.Optional;
import com.google.common.collect.ImmutableSet;
import com.googlecode.objectify.Key;
import google.registry.dns.DnsQueue;
import google.registry.flows.EppException;
import google.registry.flows.EppException.ParameterValueRangeErrorException;
import google.registry.flows.EppException.RequiredParameterMissingException;
import google.registry.flows.FlowModule.ClientId;
import google.registry.flows.FlowModule.TargetId;
import google.registry.flows.LoggedInFlow;
import google.registry.flows.TransactionalFlow;
import google.registry.model.ImmutableObject;
import google.registry.model.domain.DomainResource;
import google.registry.model.domain.metadata.MetadataExtension;
import google.registry.model.eppinput.ResourceCommand;
import google.registry.model.eppoutput.CreateData.HostCreateData;
import google.registry.model.eppoutput.EppOutput;
import google.registry.model.host.HostCommand.Create;
import google.registry.model.host.HostResource;
import google.registry.model.host.HostResource.Builder;
import google.registry.model.index.EppResourceIndex;
import google.registry.model.index.ForeignKeyIndex;
import google.registry.model.ofy.ObjectifyService;
import google.registry.model.reporting.HistoryEntry;
import javax.inject.Inject;

/**
 * An EPP flow that creates a new host.
 *
 * <p>Hosts can be "external", or "internal" (also known as "in bailiwick"). Internal hosts are
 * those that are under a top level domain within this registry, and external hosts are all other
 * hosts. Internal hosts must have at least one ip address associated with them, whereas external
 * hosts cannot have any. This flow allows creating a host name, and if necessary enqueues tasks to
 * update DNS.
 *
 * @error {@link google.registry.flows.EppXmlTransformer.IpAddressVersionMismatchException}
 * @error {@link google.registry.flows.exceptions.ResourceAlreadyExistsException}
 * @error {@link HostFlowUtils.HostNameTooLongException}
 * @error {@link HostFlowUtils.HostNameTooShallowException}
 * @error {@link HostFlowUtils.InvalidHostNameException}
 * @error {@link HostFlowUtils.SuperordinateDomainDoesNotExistException}
 * @error {@link SubordinateHostMustHaveIpException}
 * @error {@link UnexpectedExternalHostIpException}
 */
public final class HostCreateFlow extends LoggedInFlow implements TransactionalFlow {

  @Inject ResourceCommand resourceCommand;
  @Inject @ClientId String clientId;
  @Inject @TargetId String targetId;
  @Inject HistoryEntry.Builder historyBuilder;
  @Inject DnsQueue dnsQueue;
  @Inject HostCreateFlow() {}

  @Override
  @SuppressWarnings("unchecked")
  protected final void initLoggedInFlow() throws EppException {
    registerExtensions(MetadataExtension.class);
  }

  @Override
  protected final EppOutput run() throws EppException {
    Create command = (Create) resourceCommand;
    verifyResourceDoesNotExist(HostResource.class, targetId, now);
    // The superordinate domain of the host object if creating an in-bailiwick host, or null if
    // creating an external host. This is looked up before we actually create the Host object so
    // we can detect error conditions earlier.
    Optional<DomainResource> superordinateDomain = Optional.fromNullable(
        lookupSuperordinateDomain(validateHostName(targetId), now));
    verifyDomainIsSameRegistrar(superordinateDomain.orNull(), clientId);
    boolean willBeSubordinate = superordinateDomain.isPresent();
    boolean hasIpAddresses = !isNullOrEmpty(command.getInetAddresses());
    if (willBeSubordinate != hasIpAddresses) {
      // Subordinate hosts must have ip addresses and external hosts must not have them.
      throw willBeSubordinate
          ? new SubordinateHostMustHaveIpException()
          : new UnexpectedExternalHostIpException();
    }
    Builder builder = new Builder();
    command.applyTo(builder);
    HostResource newHost = builder
        .setCreationClientId(clientId)
        .setCurrentSponsorClientId(clientId)
        .setRepoId(createContactHostRoid(ObjectifyService.allocateId()))
        .setSuperordinateDomain(
            superordinateDomain.isPresent() ? Key.create(superordinateDomain.get()) : null)
        .build();
    historyBuilder
        .setType(HistoryEntry.Type.HOST_CREATE)
        .setModificationTime(now)
        .setParent(Key.create(newHost));
    ImmutableSet<ImmutableObject> entitiesToSave = ImmutableSet.of(
        newHost,
        historyBuilder.build(),
        ForeignKeyIndex.create(newHost, newHost.getDeletionTime()),
        EppResourceIndex.create(Key.create(newHost)));
    if (superordinateDomain.isPresent()) {
      entitiesToSave = union(
          entitiesToSave,
          superordinateDomain.get().asBuilder()
              .addSubordinateHost(command.getFullyQualifiedHostName())
              .build());
      // Only update DNS if this is a subordinate host. External hosts have no glue to write, so
      // they are only written as NS records from the referencing domain.
      dnsQueue.addHostRefreshTask(targetId);
    }
    ofy().save().entities(entitiesToSave);
    return createOutput(SUCCESS, HostCreateData.create(targetId, now));
  }

  /** Subordinate hosts must have an ip address. */
  static class SubordinateHostMustHaveIpException extends RequiredParameterMissingException {
    public SubordinateHostMustHaveIpException() {
      super("Subordinate hosts must have an ip address");
    }
  }

  /** External hosts must not have ip addresses. */
  static class UnexpectedExternalHostIpException extends ParameterValueRangeErrorException {
    public UnexpectedExternalHostIpException() {
      super("External hosts must not have ip addresses");
    }
  }
}
