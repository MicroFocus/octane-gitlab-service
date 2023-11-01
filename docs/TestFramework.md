## How to configure a Test Runner in GitLab

1. On your current repository (the one containing the code and the tests) create a new branch that will hold the test runner pipeline.

2. Configure the following variables on the project level:
    1. testsToRun - the value does not matter. The integration will populate this variable with the selected tests from ALM Octane.
    2. testRunnerBranch - the value will be the name of the branch created earlier, which holds the test runner pipeline.
    3. testRunnerFramework - the value should be the following:
        * mvnSurefire - For running JUnit/TestNG over Maven Surefire/Failsafe
        * uft - For running UFT-One tests using FTToolsLauncher
        * custom - For running tests using a custom Framework that can generate Junit results (see our examples [here](CustomTestFrameworkExample.md))
      
   #### Optional Variables:
    4. testRunnerCustomPattern - an optional parameter required only if the Framework is custom
                               - the value will be a JSON containing a pattern to convert the Automated Test from ALM Octane to the accepted format for the Framework
    5. suiteId -  the value does not matter. The integration will populate this variable with the selected Test Suite ID from ALM Octane.
    6. suiteRunId -  the value does not matter. The integration will populate this variable with the executed Suite Run ID from ALM Octane.
       
3. In the created branch configure the `.gitlab-ci.yml` file to include the logic for running the tests received in the testsToRun variable.
   Example for `mvnSurefire`:
    ```
   image: maven:3.3.9-jdk-8
    stages:
     - maven

    maven-code-job:
      stage: maven
      script:
      - echo "starting to run maven..."
      - mvn -Dtest=$testsToRun test

      artifacts:
        paths:
        - target/surefire-reports/*.xml
        - target/site/jacoco/*.xml

   ``` 

### How to configure a Test Runner in ALM Octane

1. Go to `Spaces`>`Devops`>`Test Runners`
2. Create a new Test Runner with a name of your choosing
3. Select the GitLab Integration CI Server
4. For the job, select the one that contains the Test Runner created on GitLab

The job dropdown will only contain jobs that have the `testsToRun` variable configured (that is how ALM Octane knows that the pipeline represents a Test Runner).

### How to run Automated Tests using a Test Runner in ALM Octane

1. Run a pipeline job that runs all the tests in order to inject them in ALM Octane as Automated Tests.
2. Create a Test Suite that includes the tests that you want to run.
3. Inside the Test Suite assign the Test Runner field to the tests.
5. Plan the Test Suite.
6. Go to the planned Suite Run, select the desired Tests, and hit the `Run` button.
7. The Test Runner will run the selected tests.

As the Test Runner executes a pipeline on the GitLab side, a job entity will also be created in ALM Octane which can be viewed in the Pipeline Section.
Keep in mind that the Test Runner pipeline should only be executed using the Test Runner and not by manually running it from the Pipeline menu.

More about ALM Octane Testing Framework here:
https://admhelp.microfocus.com/octane/en/latest/Online/Content/AdminGuide/how-setup-testing-integration.htm

### Aditional variable configuration
1. suiteId -  the value does not matter. The integration will populate this variable with the selected tests in ALM Octane.
