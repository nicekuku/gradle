/*
 * Copyright 2017 the original author or authors.
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

package org.gradle.internal.operations.notify

import org.gradle.api.internal.artifacts.configurations.ResolveConfigurationDependenciesBuildOperationType
import org.gradle.api.internal.plugins.ApplyPluginBuildOperationType
import org.gradle.configuration.ApplyScriptPluginBuildOperationType
import org.gradle.configuration.project.ConfigureProjectBuildOperationType
import org.gradle.initialization.EvaluateSettingsBuildOperationType
import org.gradle.initialization.LoadBuildBuildOperationType
import org.gradle.initialization.LoadProjectsBuildOperationType
import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import org.gradle.internal.execution.ExecuteTaskBuildOperationType
import org.gradle.internal.taskgraph.CalculateTaskGraphBuildOperationType

class BuildOperationNotificationIntegrationTest extends AbstractIntegrationSpec {

    def "obtains notifications about init scripts"() {
        when:
        executer.requireOwnGradleUserHomeDir()
        def init = executer.gradleUserHomeDir.file("init.d/init.gradle") << """
            println "init script"
        """
        buildScript """
           ${registerListenerWithDrainRecordings()}
            task t
        """

        file("buildSrc/build.gradle") << ""

        succeeds "t"

        then:
        started(ApplyScriptPluginBuildOperationType.Details, [targetType: "gradle", targetPath: null, file: init.absolutePath, buildPath: ":", uri: null])
        started(ApplyScriptPluginBuildOperationType.Details, [targetType: "gradle", targetPath: null, file: init.absolutePath, buildPath: ":buildSrc", uri: null])
    }

    def "can emit notifications from start of build"() {
        when:
        buildScript """
           ${registerListenerWithDrainRecordings()}
            task t
        """

        succeeds "t", "-S"

        then:
        started(LoadBuildBuildOperationType.Details, [buildPath: ":"])
        started(EvaluateSettingsBuildOperationType.Details, [settingsDir: testDirectory.absolutePath, settingsFile: settingsFile.absolutePath, buildPath: ":"])
        finished(EvaluateSettingsBuildOperationType.Result, [:])
        started(LoadProjectsBuildOperationType.Details, [buildPath: ":"])
        finished(LoadProjectsBuildOperationType.Result)
        started(ConfigureProjectBuildOperationType.Details, [buildPath: ':', projectPath: ':'])
        started(ApplyPluginBuildOperationType.Details, [pluginId: "org.gradle.help-tasks", pluginClass: "org.gradle.api.plugins.HelpTasksPlugin", targetType: "project", targetPath: ":", buildPath: ":"])
        finished(ApplyPluginBuildOperationType.Result, [:])
        started(ApplyScriptPluginBuildOperationType.Details, [targetType: "project", targetPath: ":", file: buildFile.absolutePath, buildPath: ":", uri: null])
        finished(ApplyScriptPluginBuildOperationType.Result, [:])
        finished(ConfigureProjectBuildOperationType.Result, [:])

        started(CalculateTaskGraphBuildOperationType.Details, [buildPath: ':'])
        finished(CalculateTaskGraphBuildOperationType.Result, [excludedTaskPaths: [], requestedTaskPaths: [":t"]])
        started(ExecuteTaskBuildOperationType.Details, [taskPath: ":t", buildPath: ":", taskClass: "org.gradle.api.DefaultTask"])
        finished(ExecuteTaskBuildOperationType.Result, [actionable: false, originExecutionTime: null, cachingDisabledReasonMessage: "Cacheability was not determined", upToDateMessages: null, cachingDisabledReasonCategory: "UNKNOWN", skipMessage: "UP-TO-DATE", originBuildInvocationId: null])
    }

    def "can emit notifications from point of registration"() {
        when:
        buildScript """
           ${registerListener()}
            task t
        """

        succeeds "t", "-S"

        then:
        // Operations that started before the listener registration are not included (even if they finish _after_ listener registration)
        notIncluded(EvaluateSettingsBuildOperationType.Details)
        notIncluded(LoadProjectsBuildOperationType.Details)
        notIncluded(ApplyPluginBuildOperationType.Details)
        notIncluded(ConfigureProjectBuildOperationType.Details)

        started(CalculateTaskGraphBuildOperationType.Details, [buildPath: ':'])
        finished(CalculateTaskGraphBuildOperationType.Result, [excludedTaskPaths: [], requestedTaskPaths: [":t"]])
        started(ExecuteTaskBuildOperationType.Details, [taskPath: ":t", buildPath: ":", taskClass: "org.gradle.api.DefaultTask"])
        finished(ExecuteTaskBuildOperationType.Result, [actionable: false, originExecutionTime: null, cachingDisabledReasonMessage: "Cacheability was not determined", upToDateMessages: null, cachingDisabledReasonCategory: "UNKNOWN", skipMessage: "UP-TO-DATE", originBuildInvocationId: null])
    }

    def "does not emit for GradleBuild tasks"() {
        when:
        def initScript = file("init.gradle") << """
            if (parent == null) {
                ${registerListener()}
            }
        """

        buildScript """
            task t(type: GradleBuild) {
                tasks = ["o"]
                startParameter.searchUpwards = false
            }
            task o
        """

        succeeds "t", "-I", initScript.absolutePath

        then:
        started(ConfigureProjectBuildOperationType.Details, [buildPath: ":", projectPath: ":"])

        // Rough test for not getting notifications for the nested build
        executedTasks.find { it.endsWith(":o") }
        recordedOps.findAll { it.detailsType == ConfigureProjectBuildOperationType.Details.name }.size() == 1
    }

    def "listeners are deregistered after build"() {
        when:
        executer.requireDaemon().requireIsolatedDaemons()
        buildFile << registerListener() << "task t"
        succeeds("t")

        then:
        finished(CalculateTaskGraphBuildOperationType.Result, [excludedTaskPaths: [], requestedTaskPaths: [":t"]])

        when:
        // remove listener
        buildFile.text = "task x"
        succeeds("x")

        then:
        recordedOps.findAll { it.detailsType == CalculateTaskGraphBuildOperationType.Result.name }.size() == 0
    }

    // This test simulates what the build scan plugin does.
    def "drains notifications for buildSrc build"() {
        given:
        file("buildSrc/build.gradle") << ""
        file("build.gradle") << """
            ${registerListenerWithDrainRecordings()}
            task t
        """

        when:
        succeeds "t"

        then:
        output.contains(":buildSrc:compileJava") // executedTasks check fails with in process executer
        recordedOps.findAll { it.detailsType == ConfigureProjectBuildOperationType.Details.name }.size() == 2
        recordedOps.findAll { it.detailsType == ExecuteTaskBuildOperationType.Details.name }.size() == 14 // including all buildSrc task execution events
    }


    def isChildren(def parent, def child) {
        assert parent.id == child.parentId
        true
    }

    def op(Class<ConfigureProjectBuildOperationType.Details> detailsClass, Map<String, String> details = [:]) {
        recordedOps.find { op ->
            return op.detailsType == detailsClass.name && op.details.subMap(details.keySet()) == details
        }
    }

    void started(Class<?> type, Map<String, ?> payload = null) {
        has(true, type, payload)
    }

    void finished(Class<?> type, Map<String, ?> payload = null) {
        has(false, type, payload)
    }

    void has(boolean started, Class<?> type, Map<String, ?> payload) {
        def typedOps = recordedOps.findAll { op ->
            return started ? op.detailsType == type.name : op.resultType == type.name
        }
        assert typedOps.size() > 0

        if (payload != null) {
            def matchingOps = typedOps.findAll { matchingOp ->
                started ? matchingOp.details == payload : matchingOp.result == payload
            }
            assert matchingOps.size()
        }
    }

    def getRecordedOps() {
        def jsonSlurper = new groovy.json.JsonSlurper()
        jsonSlurper.parse(file('buildOpNotifications.json'))
    }

    void notIncluded(Class<?> type) {
        assert !recordedOps.any { it.detailsType == type.name }
    }

    String registerListener() {
        listenerClass() + """
        registrar.registerBuildScopeListener(listener)
        """
    }

    String registerListenerWithDrainRecordings() {
        listenerClass() + """
        registrar.registerBuildScopeListenerAndReceiveStoredOperations(listener)
        """
    }

    String listenerClass() {
        """
            def listener = new ${BuildOperationNotificationListener.name}() {

                LinkedHashMap<Object,BuildOpsEntry> ops = new LinkedHashMap()
            
                @Override
                void started(${BuildOperationStartedNotification.name} startedNotification) {
            
                    def details = startedNotification.notificationOperationDetails
                    if (details instanceof org.gradle.internal.execution.ExecuteTaskBuildOperationType.Details) {
                        details = [taskPath: details.taskPath, buildPath: details.buildPath, taskClass: details.taskClass.name]
                    } else  if (details instanceof org.gradle.api.internal.plugins.ApplyPluginBuildOperationType.Details) {
                        details = [pluginId: details.pluginId, pluginClass: details.pluginClass.name, targetType: details.targetType, targetPath: details.targetPath, buildPath: details.buildPath]
                    }

                    ops.put(startedNotification.notificationOperationId, new BuildOpsEntry(id: startedNotification.notificationOperationId?.id,
                            parentId: startedNotification.notificationOperationParentId?.id,
                            detailsType: startedNotification.notificationOperationDetails.getClass().getInterfaces()[0].getName(),
                            details: details, 
                            started: startedNotification.notificationOperationStartedTimestamp))
                }
            
                @Override
                void finished(${BuildOperationFinishedNotification.name} finishedNotification) {
                    def result = finishedNotification.getNotificationOperationResult()
                    if (result instanceof ${ResolveConfigurationDependenciesBuildOperationType.Result.name}) {
                        result = []
                    }
                    def op = ops.get(finishedNotification.notificationOperationId)
                    op.resultType = finishedNotification.getNotificationOperationResult().getClass().getInterfaces()[0].getName()
                    op.result = result
                    op.finished = finishedNotification.getNotificationOperationFinishedTimestamp()
                }
            
                void store(File target){
                    target.withPrintWriter { pw ->
                        String json = groovy.json.JsonOutput.toJson(ops.values());
                        pw.append(json)
                    }
                }
            
                static class BuildOpsEntry {
                    Object id
                    Object parentId
                    Object details
                    Object result
                    String detailsType
                    String resultType
                    long started
                    long finished
                }
            }

            def registrar = services.get($BuildOperationNotificationListenerRegistrar.name)            
            gradle.buildFinished {
                listener.store(file('${file('buildOpNotifications.json').toURI()}'))
            }
        """
    }

}
