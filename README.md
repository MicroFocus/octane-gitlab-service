![OPENTEXT LOGO](https://upload.wikimedia.org/wikipedia/commons/1/1b/OpenText_logo.svg)

[![Build status](https://ci.appveyor.com/api/projects/status/github/MicroFocus/octane-gitlab-service?branch=master&svg=true)](https://ci.appveyor.com/project/OctaneCIPlugins/octane-gitlab-service)

## Relevant links

-	**Download the most recent LTS version of the service** at [Github releases](https://github.com/MicroFocus/octane-gitlab-service/releases)
-	**Check the open issues (and add new issues)** at [Github issues](https://github.com/MicroFocus/octane-gitlab-service/issues)


# ALM Octane GitLab CI service

This service integrates ALM Octane with GitLab, enabling ALM Octane to display GitLab build pipelines and track build and test run results.


#### Integration flow:

The service communicates both with the GitLab server and the Octane server.
Both sides of communication must be reachable. 

##### Communication with Gitlab:

* The service sends an API requests to GitLab server.  
For example: check permission, get project list, get test results, run project.  

* GitLab sends events to this service.  
For example: start and finish running of project.  
The service registers to events using the GitLab project webhook mechanism.

_**Note that if the service is down or unavailable, the data will be lost and not displayed in ALM Octane.**_  



##### Communication with Octane:

The service makes use of the Octane [CI SDK](https://github.com/MicroFocus/octane-ci-java-sdk) for sending CI events to ALM Octane (pipeline started/job started/SCM data etc.).




## Installation and configuration instructions

1. Create a new directory to serve as the installation target, hosted on any machine that can access GitLab. (If GitLab is behind a firewall, this machine must also be behind it.)
2. Download the octane-gitlab-service-\<version\>.jar file from the release assets and copy it to the target directory.
3. In ALM Octane, create an API access Client ID and Client secret with the CI/CD Integration role.
4. In GitLab, create a personal access token with read/write over API and read over repository scopes for the integration user. The integration user should have a MAINTAINER role inside the projects/groups which you want to integrate in ALM Octane.
5. In the target directory create a new application.properties file. Populate it with the following properties, and modify the placeholders with the appropriate values.
```application.properties
# Jetty oriented properties
# =========================
server.port=9091


# ALM Octane GitLab Service-oriented properties
# =========================================
server.baseUrl=<mandatory: the base URL of this service (the URL on which the GitLab service actually runs), should be accessible by GitLab>
server.webhook.route.url=<optional: the webhook URL of the GitLab service that can be exposed and is reachable from GitLab (the URL that GitLab will send events to)>
ciserver.identity=<optional: CI server identity, by default a hash value generated from the service URL is used>
octane.location=<mandatory: the URL of the ALM Octane server with the /ui path and the sharedspace parameters. Example: https://myserver:8080/ui?p=1005>
octane.apiClientID=<mandatory: ALM Octane API client ID with CI/CD integration role over the target workspace(s)>
octane.apiClientSecret=<sensitive,mandatory: ALM Octane API client secret>
gitlab.location=<mandatory: the base URL of the GitLab server>
gitlab.personalAccessToken=<sensitive,mandatory: GitLab personal access token>
gitlab.testResultsFilePattern=<optional: 'glob:pattern' or 'regex:pattern' pattern for finding the test result files inside GitLab job artifact ZIP>.
For complete documentation of the applicable patterns see https://docs.oracle.com/javase/8/docs/api/java/nio/file/FileSystem.html#getPathMatcher-java.lang.String-
gitlab.gherkinTestResultsFilePattern=<optional:'glob:pattern' or 'regex:pattern' pattern for finding Gherkin test result files inside GitLab job artifact ZIP>
gitlab.testResultsOutputFolderPath=<optional: place to save test results before sending it to Octane.>
gitlab.variables.pipeline.usage=<optional: comma separated list of project,groups,instance>
gitlab.ci.service.can.run.pipeline=<optional: If the service can run pipelines from ALM Octane. true by default>

gitlab.codeCoverage.generatedCoverageReportFilePathVarName=<optional: Name of the variable containing the path of the generated JaCoCo coverage report file as configured in the pipeline. Default value: jacocoReportPath>
gitlab.mergeRequests.variables.publishMergeRequestVarName=<optional: Name of the variable containing a boolean value that specifies if merge requests will be published into Octane from the current project. Default value: 'publishMergeRequests'>
gitlab.mergeRequests.variables.destinationWorkspaceVarName=<optional: Name of the variable containing a string value that specifies the destination workspace id where merge requests will be published into Octane from the current project. Default value: 'destinationWorkspace'>
gitlab.mergeRequests.variables.useSSHFormatVarName=<optional: Name of the variable containing a boolean value that specifies if the clone url for the current proeject should be in SSH format or not. Default value: 'useSSHFormat'>
gitlab.mergeRequests.mergeRequestHistoryFolderPath=<optional: Path of directory to store fetch history state for projects. Default value: 'projectHistory'>

# HTTP(S) proxy oriented properties
# =================================
#http.proxyUrl=<optional: http proxy URL>
#http.proxyUser=<optional: http proxy username>
#http.proxyPassword=<sensitive,optional: http proxy password>
#http.nonProxyHosts=<optional: comma separated list of hosts to access without the http proxy>
#https.proxyUrl=<optional: https proxy URL>
#https.proxyUser=<optional: https proxy username>
#https.proxyPassword=<sensitive,optional: https proxy password>
#https.nonProxyHosts=<optional: comma separated list of hosts to access without the https proxy>
```
6. From a command line prompt open on the target directory, run the command line below:
```bash
java –jar octane-gitlab-service-<version>.jar
```

### Properties information:

##### server.port
The TCP port for this service to listen on.

##### server.baseUrl
The base URL of this service should be accessible by GitLab.

Example:

    http://myServiceServer.myCompany.com:9091

##### server.webhook.route.url
If GitLab is in another network and can't access <server.baseUrl>\events, insert here the accessible URL
and route this **<server-based URL>\events** to **<server-based URL>\events**.

##### ciserver.identity
The identity of the GitLab server in the ALM Octane server. By default, a hash value generated from the service URL is used.
Overriding this property is helpful in preserving the CI server identity while migrating GitLab to another location.

##### octane.location
The URL of the ALM Octane server, using its fully qualified domain name (FQDN).

Use the following format (port number is optional):

    http://<ALM Octane hostname / IP address> {:<port number>}/ui/?p=<shared space ID>

Example:  
In this URL, the shared space ID is 1002:

    http://myALMOctaneServer.myCompany.com:8081/ui/?p=1002

**Tip: You can copy the URL from the address bar of the browser in which you opened ALM Octane.**

##### octane.apiClientID
The API access Client ID that the plugin should use to connect to ALM Octane.
##### octane.apiClientSecret
The API Client secret that the plugin should use to connect to ALM Octane. This is a sensitive token. See the 'Password Encryption' section below.
##### gitlab.location
The base URL of GitLab.
Example:

    http://myGitLabServer.myCompany.com:30080
##### gitlab.personalAccessToken
A personal access token for a particular user in GitLab, the user should be a member of the projects/group with MAINTAINER rights, and the scopes of the token should be read/write over API and read over repository. This is a sensitive token. See the 'Password Encryption' section below.
##### gitlab.testResultsFilePattern
A 'glob:pattern' or 'regex:pattern' pattern for finding the test result files inside GitLab job artifact ZIP.
For complete documentation about the applicable patterns see [java.nio.file.FileSystem::getPathMatcher](https://docs.oracle.com/javase/8/docs/api/java/nio/file/FileSystem.html#getPathMatcher-java.lang.String-)
Note: In order to report test results to ALM Octane, a GitLab job must add the test results file into its artifacts at a path that matches this pattern.
Example (any XML file recursively):

     glob:**.xml

##### gitlab.gherkinTestResultsFilePattern #####  
Same as testResultsFilePattern property, but for Gherkin test results.

##### gitlab.testResultsOutputFolderPath #####
Path to directory in GitLab service machine, where Gherkin test results will be stored until sent to Octane. 
After one hour the files will be deleted. 
    
##### gitlab.variables.pipeline.usage
By default, the service report to ALM Octane includes all of the project's variables.  
These variables will be present in the Pipeline module and the user can set new values for them and run the pipeline from ALM Octane.

To also see the groups or instance variables, add them to the property’s value.
Example:   
       
* Variables from project: gitlab.variables.pipeline.usage=project   

* Variables from project, groups and from instance: gitlab.variables.pipeline.usage=project,groups,instance
  
* Variables from group only: gitlab.variables.pipeline.usage=groups

##### gitlab.ci.service.can.run.pipeline
By default, the user can also run pipelines from ALM Octane.
When ALM Octane users are not allowed to trigger the pipeline, the parameter should be set to 'false'. 

##### gitlab.mergeRequests.variables.publishMergeRequestVarName
The name of the GitLab variable that specifies if merge requests should be sent to Octane or not for the project where
the variable is defined.

The values must be boolean (true or false).

If the variable is not defined in Gitlab or if it is set to anything but true then merge requests will not be sent to 
Octane for the current project.

##### gitlab.mergeRequests.variables.destinationWorkspaceVarName
The name of the GitLab variable that specifies the destination workspace id for the merge requests.

If the project has merge request sending set to true (the publishMergeRequest variable) but does not have this variable
defined or if the workspace id specified does not exist an error will be thrown.

Rule of thumb: Where publishMergeRequest variable is set to true, destinationWorkspace variable must also be configured 
in order for the tool to work correctly.

##### gitlab.mergeRequests.variables.useSSHFormatVarName
The name of the GitLab variable that specifies which URL of the repository should be used for cloning.

The values must be boolean (true or false).

If the value is set to true, the ssh URL will be used. Otherwise, if the value is not true or if the variable is not 
defined at all for the project, then the HTTP URL will be used by default.

#### gitlab.codeCoverage.variables.generatedCoverageReportFilePathVarName
The name of the GitLab variable that specifies the path to the generated coverage report file by the pipeline job.

This path should be the one that is configured in the pipeline coverage goal step.

Example value of the variable content: target/site/jacoco/jacoco.xml

##### gitlab.mergeRequests.mergeRequestHistoryFolderPath
The path to a folder where merge request history fetching statuses should be kept for each of the projects.

If the folder does not exist, then it will be created automatically.

In this folder, an empty file for each of the projects will be created (the name of the file will be the id of each 
project). The meaning of the files is the following: if a file named with an id of a project exists, it means that merge 
request history has been fetched for that project.

The tool consistently listens for changes in the directory and if one file for a specific project is deleted, then it will automatically fetch the merge request history for that project and recreate the file.

## Configuring variables in Gitlab

To configure variables inside a project you must do the following:
1. While inside the project click on Settings -> CI/CD
2. Go to the Variables section and click "Expand"
3. Click on the "Add Variable" button
4. Fill in the "Key" and "Value" fields and then click the "Add Variable" button

The key of the variable is the name that should be configured in the application.properties file for variable name 
properties (gitlab.variables.publishMergeRequestVarName, gitlab.variables.destinationWorkspaceVarName,
gitlab.variables.useSSHFormatVarName).

## Usage instructions

1. Create a new GitLab CI server entity in ALM Octane.
2. Go to the Pipelines module.
3. Create a pipeline using the newly created GitLab CI server.
4. The list only displays pipelines in which the integration user has a MAINTAINER role.
5. Run the pipeline and wait for the run to finish.
6. Look at the result – statuses, topology, build entries, and test entries.

## Integration with ALM Octane Test Framework

The ALM Octane Test Framework allows the running of separate automated tests from ALM Octane using Test Runners.(how to configure [here](docs/TestFramework.md))

## Cleanup webhooks

It is possible to start the service in clean mode.  
This mode allows you to clear all hooks from the GitLab server.

To clean the webhooks run the following command line:

```  
java –jar octane-gitlab-service-<version>.jar --cleanupOnly=true
```
## Password encryption

Values of the properties marked as 'sensitive' in the above list can be encrypted before writing them down in the *'application.properties'* file. To encrypt a sensitive token, run the following command line:

```bash
java –jar octane-gitlab-service-<version>.jar encrypt <sensitive_token>
``` 

The result should look like this:

```bash
Encrypted token: AES:oLPF209dEPuL69RUxfG6Wg==:HOsPY6YTUj2OG5aVNtp/xQ==
```

The encrypted token that starts with 'AES:' can be directly copied (including the 'AES:' prefix) to the *'application.properties'* file.
However, password encryption is optional. You can enter the password plain values directly.
## SSL port
[How to configure the service with SSL port ](https://mkyong.com/spring-boot/spring-boot-ssl-https-examples/)  

## Troubleshooting

### Multi-Branch 

If you create a pipeline with one branch, and then add more branches, ALM Octane will not reflect this change.  
In this case, delete the original pipeline and create a new one for the multi-branch plan.

### Cannot see the project in the job list of Octane

The project must contain files and not be empty. Make sure there is at least one branch in your GitLab project.

### GitLab WebHooks

If you can run the pipeline from Octane but cannot see progress and results:  
Check the webhook from your project in GitLab (in GitLab go to your `Project`>`Settings`>`Integration` and test the webhooks for your service URL: <service Url>\events).
If necessary, update the application.properties file with the correct server.baseurl value.


### Endpoint not accessible by GitLab

If you are getting this error:  
```
GitlabServices: Error while accessing the '<service-url>/events' endpoint.  
Note that this endpoint must be accessible by GitLab.
```
In the service we are running a test for the webhook URL, to see if the endpoint is accessible.
If it is not accessible, please check your application.properties file.
In case this URL is not accessible from GitLab (for example if GitLab is in a different network),  
you can use the “server.webhook.route.url” property to fix your environment.
 

### GitLab Runner HTTP(S) proxy

GitLab uses registered runners for running pipelines. Runners are registered as described in this article: https://docs.gitlab.com/runner/register/.
If a runner communicates with the integration service over a network that requires HTTP(S) proxy settings, an easy way to set the proxy is to add it to the runner’s registration entry.

For example, after registering a runner on a Linux machine, its registration entry resides in the /etc/gitlab-runner/config.toml file. (Tip: If you can’t find this file, run the ‘gitlab-runner list’ shell command – it displays the registration file location as ConfigFile=…). This entry may look as below:

```
[[runners]]
  name = "my-runner"
  url = "http://my-runner.cloudlab.net:30080/"
  token = "4329809ufewjfewkfjnoeihtrjfoif"
  executor = "shell"
  builds_dir = "/workspace"
  [runners.cache]
```

In order to set the HTTP(S) proxy, an “environment” row should be added to this entry (replace the URL placeholders with the correct values):

```
[[runners]]
  name = "my-runner"
  url = "http://gitlab-placeholder.net:30080/"
  token = "4329809ufewjfewkfjnoeihtrjfoif"
  executor = "shell"
  builds_dir = "/workspace"
  environment = ["HTTPS_PROXY=http://proxy-placeholder.net:8080", "HTTP_PROXY=http://proxy-placeholder.net:8080"]
  [runners.cache]
```

After adding the proxy settings, restart the gitlab-runner service.

### Logging (Log4J 2 Configuration)

The project supports Log4J 2 configuration. In application.properties use the property below for specifying the Log4J 2 configuration file.
```
logging.config
```

For example:
```
logging.config=./log4j2.xml
``` 

Below is an example of a Log4J 2 XML configuration:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<Configuration status="warn">
    <Properties>
        <Property name="basePath">./logs</Property>
    </Properties>
 
    <Appenders>
        <RollingFile name="fileLogger" fileName="${basePath}/app-info.log" filePattern="${basePath}/archive/app-info-%d{yyyy-MM-dd}.log">
            <PatternLayout>
                <pattern>[%-5level] %d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %c{1} - %msg%n</pattern>
            </PatternLayout>
            <Policies>
                <TimeBasedTriggeringPolicy interval="1" modulate="true" />
            </Policies>
        </RollingFile> 
        <Console name="console" target="SYSTEM_OUT">
            <PatternLayout pattern="[%-5level] %d{yyyy-MM-dd HH:mm:ss.SSS} [%t] %c{1} - %msg%n" />
        </Console>
    </Appenders>
    <Loggers>
        <Root level="info">
            <appender-ref ref="console" />
            <appender-ref ref="fileLogger" />
        </Root>
    </Loggers>
</Configuration>
``` 

### Allowing requests to the local network

If the GitLab server and the octane-gitlab-service app both run on the same network, you need to enable "Allow requests to the local network from hooks and services" as follows:
- Open the [your_gitlab_server]/admin/application_settings page.
- In the `Outbound requests` section, check the `Allow requests to the local network from hooks and services` checkbox.

### SSL error 

If getting the following error in the log:
"javax.net.ssl.SSLHandshakeException: sun.security.validator.ValidatorException: PKIX path building failed:   
sun.security.provider.certpath.SunCertPathBuilderException: unable to find valid certification path to requested target"
- try to install the certificate manually:  
example from customer process: 
```
# add ca cert
cd /usr/share/your-server/jre/lib/security
/usr/share/your-server/jre/bin/keytool -import -storepass changeit -noprompt -alias cacert -keystore cacerts -trustcacerts -file /tmp/certificadoOctane.cer
 
# add octane\gitlab server cert
cd /usr/share/your-server/jre/lib/security
/usr/share/your-server/jre/bin/keytool -import -storepass changeit -noprompt -alias octane-cert -keystore cacerts -trustcacerts -file /tmp/octanecielo.crt
 
# check if java is using the right ca/cert
cd /tmp
[root@ip-172-xxx-xxx-xxx tmp]# wget https://confluence.atlassian.com/download/attachments/225122392/SSLPoke.class?version=1&modificationDate=1288204937304&api=v2
[root@ip-172-xxx-xxx-xxx tmp]# mv SSLPoke.class\?version\=1 SSLPoke.class
 
[root@ip-172-xxx-xxx-xxx tmp]# java -Djavax.net.ssl.trustStore=/usr/share/your-server/jre/lib/security/cacerts  SSLPoke octanecielo.enterprisetrn.hdevelo.com.br 443
Successfully connected
Procedure
```
