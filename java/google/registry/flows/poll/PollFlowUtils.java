// Copyright 2016 The Domain Registry Authors. All Rights Reserved.
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

package google.registry.flows.poll;

import static google.registry.model.ofy.ObjectifyService.ofy;

import com.googlecode.objectify.cmd.Query;
import google.registry.model.poll.PollMessage;
import org.joda.time.DateTime;

/** Static utility functions for poll flows. */
public final class PollFlowUtils {

  private PollFlowUtils() {}

  /** Returns a query for poll messages for the logged in registrar which are not in the future. */
  static Query<PollMessage> getPollMessagesQuery(String clientId, DateTime now) {
    return ofy().load()
        .type(PollMessage.class)
        .filter("clientId", clientId)
        .filter("eventTime <=", now.toDate())
        .order("eventTime");
  }
}