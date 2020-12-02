/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH
 * under one or more contributor license agreements. See the NOTICE file
 * distributed with this work for additional information regarding copyright
 * ownership. Camunda licenses this file to you under the Apache License,
 * Version 2.0; you may not use this file except in compliance with the License.
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

import { AngularApp } from "components";
import React from "react";
import { useParams } from "react-router-dom";
import angularComponent from "./angularComponent";
import { ActivityProvider } from "./HOC/withActivityInstances";
import { BpmnProvider } from "./HOC/withBpmn";
import { ProcessInstanceProvider } from "./HOC/withProcessInstance";

export default function ProcessInstance() {
  const { id } = useParams();

  return (
    <ProcessInstanceProvider processInstanceId={id}>
      <ActivityProvider processInstanceId={id}>
        <BpmnProvider>
          <AngularApp component={angularComponent} />
        </BpmnProvider>
      </ActivityProvider>
    </ProcessInstanceProvider>
  );
}
