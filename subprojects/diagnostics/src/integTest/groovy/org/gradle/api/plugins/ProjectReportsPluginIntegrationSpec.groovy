/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.api.plugins

import org.gradle.integtests.fixtures.AbstractIntegrationSpec
import spock.lang.Unroll

class ProjectReportsPluginIntegrationSpec extends AbstractIntegrationSpec {
    def setup() {
        buildFile << """
            apply plugin: 'project-report'
        """
    }

    def "produces report files"() {
        when:
        succeeds("projectReport")

        then:
        file("build/reports/project/dependencies.txt").assertExists()
        file("build/reports/project/properties.txt").assertExists()
        file("build/reports/project/tasks.txt").assertExists()
        file("build/reports/project/dependencies").assertIsDir()
    }

    def "produces report files in custom directory"() {
        given:
        buildFile << """
            projectReportDirName = "custom"
        """

        when:
        succeeds("projectReport")

        then:
        file("build/reports/custom/dependencies.txt").assertExists()
        file("build/reports/custom/properties.txt").assertExists()
        file("build/reports/custom/tasks.txt").assertExists()
        file("build/reports/custom/dependencies").assertIsDir()
    }

    @Unroll
    def "prints link to default #task"(String task) {
        when:
        succeeds(task)

        then:
        outputContains("See the report at:")

        where:
        task << ["taskReport", "propertyReport", "dependencyReport"]
    }
}
