# octane-gitlab-service

## Usage
- Download the octane-gitlab-service-*.jar file from the release assets and copy it to the target directory.
-	Create a “CI/CD Integration” API access in Octane.
-	Create an API private token for a particular user in GitLab.
-	In the target directory create a new application.properties file, populate it with the following properties and modify the placeholders with the appropriate values.
```application.properties
# Jetty oriented properties
# =========================
server.port=9091


# Octane GitLab Service oriented properties
# =========================================
server.baseUrl=<mandatory: the base URL of this service, should be accessible by GitLab>
#ciserver.identity=<optional: CI server identity, by default a hash value generated from the serice URL is used>
octane.location=<mandatory: the base URL of the Octane server>
octane.sharedspace=<mandatory: the target Octane shared space>
octane.apiClientID=<mandatory: Octane API client ID with CI/CD integration role over the target workspace(s)>
octane.apiClientSecret=<mandatory: Octane API client secret>
gitlab.location=<mandatory: the base URL of the GitLab server>
gitlab.personalAccessToken=<mandatory: GitLab API private token>
gitlab.testResultsFilePattern=<optional: glob pattern for finding the test result files inside GitLab job artifact ZIP>


# HTTP(S) proxy oriented properties
# =================================
#http.proxyUrl=<optional: http proxy URL>
#http.proxyUser=<optional: http proxy username>
#http.proxyPassword=<optional: http proxy password>
#http.nonProxyHosts=<optional: comma separated list of hosts to access without the http proxy>
#https.proxyUrl=<optional: https proxy URL>
#https.proxyUser=<optional: https proxy username>
#https.proxyPassword=<optional: https proxy password>
#https.nonProxyHosts=<optional: comma separated list of hosts to access without the https proxy>
```
-	From a command line prompt open on the target directory, run the command line below:
```bash
java –jar the octane-gitlab-service-*.jar
```
-	Create a new GitLab CI server entity in Octane.
-	Go to the Pipelines module.
-	Create a new pipeline from the new GitLab CI server.
-	Observation: only pipelines owned by the user whose API access was used are shown in the list.
-	Run the pipeline and wait for the run finish.
-	Look at the result – statuses, topology, build entries, test entries.
-	Modify anything in the code – a new build is automatically triggered.
-	Look at the results again, especially notice the “Commits” part.
