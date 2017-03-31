/*
 * Copyright 2015 the original author or authors.
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


package org.gradle.integtests.tooling.r25

import org.gradle.integtests.tooling.fixture.ProgressEvents
import org.gradle.integtests.tooling.fixture.TargetGradleVersion
import org.gradle.integtests.tooling.fixture.ToolingApiSpecification
import org.gradle.integtests.tooling.fixture.ToolingApiVersion
import org.gradle.tooling.BuildException
import org.gradle.tooling.ListenerFailedException
import org.gradle.tooling.ProjectConnection
import org.gradle.tooling.events.OperationType
import org.gradle.tooling.events.ProgressEvent
import org.gradle.tooling.model.gradle.BuildInvocations

class BuildProgressCrossVersionSpec extends ToolingApiSpecification {
    @ToolingApiVersion(">=2.5")
    @TargetGradleVersion(">=1.2 <2.5")
    def "ignores listeners when Gradle version does not generate build events"() {
        given:
        goodCode()

        when:
        withConnection {
            ProjectConnection connection ->
                connection.newBuild().forTasks('assemble').addProgressListener({ ProgressEvent event ->
                    throw new RuntimeException()
                }, EnumSet.of(OperationType.GENERIC)).run()
        }

        then:
        noExceptionThrown()
    }

    @ToolingApiVersion(">=2.5")
    @TargetGradleVersion(">=2.5")
    def "receive build progress events when requesting a model"() {
        given:
        goodCode()

        when: "asking for a model and specifying some task(s) to run first"
        def events = ProgressEvents.create()
        withConnection {
            ProjectConnection connection ->
                connection.model(BuildInvocations).forTasks('assemble').addProgressListener(events, EnumSet.of(OperationType.GENERIC)).get()
        }

        then: "build progress events must be forwarded to the attached listeners"
        events.assertIsABuild()
    }

    @ToolingApiVersion(">=2.5")
    @TargetGradleVersion(">=2.5")
    def "receive build progress events when launching a build"() {
        given:
        goodCode()

        when: "launching a build"
        def events = ProgressEvents.create()
        withConnection {
            ProjectConnection connection ->
                connection.newBuild().forTasks('assemble').addProgressListener(events, EnumSet.of(OperationType.GENERIC)).run()
        }

        then: "build progress events must be forwarded to the attached listeners"
        events.assertIsABuild()
    }

    @ToolingApiVersion(">=2.5")
    @TargetGradleVersion(">=2.5")
    def "stops dispatching events to progress listeners when a listener fails and continues with build"() {
        given:
        goodCode()

        when: "launching a build"
        List<ProgressEvent> resultsOfFirstListener = []
        List<ProgressEvent> resultsOfLastListener = []
        def stdout = new ByteArrayOutputStream()
        def failure = new IllegalStateException("Throwing an exception on purpose")
        withConnection {
            ProjectConnection connection ->
                connection.newBuild().forTasks('assemble').addProgressListener({ ProgressEvent event ->
                    resultsOfFirstListener.add(event)
                }, EnumSet.of(OperationType.GENERIC)).addProgressListener({ ProgressEvent event ->
                    throw failure
                }, EnumSet.of(OperationType.GENERIC)).addProgressListener({ ProgressEvent event ->
                    resultsOfLastListener.add(event)
                }, EnumSet.of(OperationType.GENERIC)).setStandardOutput(stdout).run()
        }

        then: "listener exception is wrapped"
        ListenerFailedException ex = thrown()
        ex.message.startsWith("Could not execute build using")
        ex.causes == [failure]

        and: "expected events received"
        resultsOfFirstListener.size() == 1
        resultsOfLastListener.size() == 1

        and: "build execution is successful"
        stdout.toString().contains("BUILD SUCCESSFUL")
    }

    @ToolingApiVersion(">=2.5")
    @TargetGradleVersion(">=2.5")
    def "receive build progress events for successful operations"() {
        given:
        goodCode()

        when:
        def events = ProgressEvents.create()
        withConnection {
            ProjectConnection connection ->
                connection.newBuild().forTasks('classes').addProgressListener(events, EnumSet.of(OperationType.GENERIC)).run()
        }

        then:
        events.assertIsABuild()

        // Verify the most interesting operations; there may be others
        def runBuild = events.operation("Run build")
        runBuild.descriptor.name == "Run build"
        runBuild.descriptor.parent == null

        def configureBuild = events.operation("Configure build")
        configureBuild.descriptor.name == "Configure build"
        configureBuild.descriptor.parent == runBuild.descriptor

        def runTasks = events.operation("Run tasks")
        runTasks.descriptor.name == "Run tasks"
        runTasks.descriptor.parent == runBuild.descriptor

        events.operations[0] == runBuild

        events.operations.each { it.successful }
        events.operations.each { it.buildOperation }
    }

    @ToolingApiVersion(">=2.5")
    @TargetGradleVersion(">=2.5")
    def "receive build progress events for failed operations"() {
        given:
        buildFile << """
            apply plugin: 'java'
            repositories { mavenCentral() }
            dependencies { testCompile 'junit:junit:4.12' }
            compileTestJava.options.fork = true  // forked as 'Gradle Test Executor 1'
        """

        file("src/test/java/MyTest.java") << """
            package example;
            public class MyTest {
                @org.junit.Test public void foo() throws Exception {
                     Thread.sleep(100);  // sleep for a moment to ensure test duration is > 0 (due to limited clock resolution)
                     throw new RuntimeException("broken", new RuntimeException("nope"));
                }
            }
        """

        when:
        def events = ProgressEvents.create()
        withConnection {
            ProjectConnection connection ->
                connection.newBuild().forTasks('test').addProgressListener(events, EnumSet.of(OperationType.GENERIC)).run()
        }

        then:
        thrown(BuildException)

        then:
        events.assertIsABuild()

        // The main operations; there may be others
        def runBuild = events.operation("Run build")
        runBuild.descriptor.parent == null
        runBuild.failed
        runBuild.failures.size() == 1

        def configureBuild = events.operation("Configure build")
        configureBuild.descriptor.parent == runBuild.descriptor
        configureBuild.successful

        def runTasks = events.operation("Run tasks")
        assert runTasks.descriptor.parent == runBuild.descriptor
        runTasks.failed
        runTasks.failures.size() == 1

        events.operations[0] == runBuild
        events.operations.each { it.buildOperation }

        events.failed == [runBuild, runTasks]
        events.successful == events.operations - [runBuild, runTasks]
    }

    def goodCode() {
        buildFile << """
            apply plugin: 'java'
            compileJava.options.fork = true  // forked as 'Gradle Test Executor 1'
        """

        file("src/main/java/example/MyClass.java") << """
            package example;
            public class MyClass {
                public void foo() throws Exception {
                    Thread.sleep(100);
                }
            }
        """
    }

}
