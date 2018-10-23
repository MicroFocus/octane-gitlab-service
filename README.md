# octane-gitlab-service

## Usage
- Download the octane-gitlab-service-*.jar file from the release assets and copy it to the target directory.
-	Create a “CI/CD Integration” API access in Octane.
-	Create a personal access token for a particular user in GitLab.
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
octane.apiClientSecret=<sensitive,mandatory: Octane API client secret>
gitlab.location=<mandatory: the base URL of the GitLab server>
gitlab.personalAccessToken=<sensitive,mandatory: GitLab personal access token>
gitlab.testResultsFilePattern=<optional: 'glob:pattern' or 'regex:pattern' pattern for finding the test result files inside GitLab job artifact ZIP>.
For complete documentation of the applicable patterns see [this doc](https://docs.oracle.com/javase/8/docs/api/java/nio/file/FileSystem.html#getPathMatcher-java.lang.String-)


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
-	From a command line prompt open on the target directory, run the command line below:
```bash
java –jar octane-gitlab-service-<version>.jar
```
-	Create a new GitLab CI server entity in Octane.
-	Go to the Pipelines module.
-	Create a new pipeline from the new GitLab CI server.
-	Observation: only pipelines owned by the user whose API access was used are shown in the list.
-	Run the pipeline and wait for the run finish.
-	Look at the result – statuses, topology, build entries, test entries.
-	Modify anything in the code – a new build is automatically triggered.
-	Look at the results again, especially notice the “Commits” part.

## Password encryption

All the sensitive tokens in the *'application.properties'* file can be encrypted. For encrypting a sensitive token, run the following command line:

```bash
java –jar octane-gitlab-service-<version>.jar encrypt <sensitive_token>
``` 

The result should look like:

```bash
Encrypted token: AES:oLPF209dEPuL69RUxfG6Wg==:HOsPY6YTUj2OG5aVNtp/xQ==
```

The encrypted token that starts with 'AES:' can be directly copied(including the 'AES:' prefix) to the *'application.properties'* file.
However, password encryption is optional. One can put the password plain values directly.
