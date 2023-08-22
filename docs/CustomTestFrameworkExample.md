### Structure of the testRunnerCustomPattern json

Only two keys are required in the json:
1. testPattern 
   * the value will be a combination of the following three fields form the Automated Test : `Package`, `Class name`, `Name`
   * it represents the structure of the tests form the `testsToRun` parameter
2. testDelimiter 
   * a delimiter character or set of characters for the tests form the testsToRun parameter

#### Simple Example: 
We have a framework that will generate a Junit report with the following structure:

    <?xml version="1.0" encoding="utf-8"?>
        <testsuites>
            <testsuite name="suiteName" errors="0" failures="0" skipped="0" tests="3">
                <testcase classname="className1" name="test1" />
                <testcase classname="className1" name="test2" />
                <testcase classname="className2" name="test3" />
            </testsuite>
        </testsuites>

When Injected in ALM Octane will create the following three Automated Tests:
* Name: test1, Class name: ../className1, Package: <empty>
* Name: test2, Class name: ../className1, Package: <empty>
* Name: test3, Class name: ../className2, Package: <empty>

Our framework supports the next format for executing multiple tests `myframework package:classname#test, ...`, so we need to convert our data from the fields to be sent via the `testsToRun` parameter with the correct format.

In this case the json value for the `testRunnerCustomPattern` parameter:

        {
            "testPattern": "$package:$class#$testName",
            "testDelimiter": ", "
        }

* `"testPattern": "$package:$class#$testName"` - will map our fields form the Automated Test (`Package`, `Class name`, `Name`) to the accepted format of the framework `package:classname#test`.
* `"testDelimiter": ", "` - will separate each test with a comma.

The value for the `$testsToRun` parameter (when all the Automated Tests are selected to run in the Suite Run) will be `className1#test1, className1#test2, className2#test3`. (Package will be ignored since is empty)

The following optional keys are allowed as well:
1. suffix/prefix
    * the value is a character or a set of character that will be added at the beginning/end of the `testsToRun` parameter.
    * it can be used to add new option in the command call for the chosen framework
2. replacements
   * the value is a list of json containing a replacement object
   * it can be used to replace/modify the string values of the fields form the Automated Test (`Package`, `Class name`, `Name`)
   * the replacement objects are of the following types: 
     1. `replaceString` - will replace every occurrence of a given `string` with a `replacement` in the `target` (`$package` and or `$class` and or `testName`)
            
             {
                  "type": "replaceString",
                  "target": "$package|$class|$testName"
                  "string": "<stringToRepace>"
                  "replacement": "<repacementString>"
             }
     
     2. `replaceRegex` - will replace every occurrence of a given `regex` with a `replacement` in the `target` (`$package` and or `$class` and or `testName`)
        
            {
               "type": "replaceRegex",
               "target": "$package|$class|$testName"
               "regex": "<regexToReplace>"
               "replacement": "<repacementString>"
            }

     3. `replaceRegexFirst` - will replace first occurrence of a given `regex` with a `replacement` in the `target` (`$package` and or `$class` and or `testName`)

            {
               "type": "replaceRegexFirst",
               "target": "$package|$class|$testName"
               "regex": "<regexToReplace>"
               "replacement": "<repacementString>"
            }
     
     4. `notLatinAndDigitToOctal` - convert every non latin or digit character to its octal representation in the `target` (`$package` and or `$class` and or `testName`)

            {
               "type": "notLatinAndDigitToOctal",
               "target": "$package|$class|$testName"
            }
     
     5. `joinString` - will add a `sufix` and or a `prefix` to the `target` (`$package` and or `$class` and or `testName`)

            {
               "type": "joinString",
               "target": "$package|$class|$testName"
               "sufix": "<sufixToAdd>"
               "prefix": "<prefixToAdd>"
            }
     
     6. `toUpperCase` - will convert the `target` (`$package` and or `$class` and or `testName`) to upper case

            {
               "type": "toUpperCase",
               "target": "$package|$class|$testName"
            }

     7. `toLowerCase` - will convert the `target` (`$package` and or `$class` and or `testName`) to lower case

            {
               "type": "toLowerCase",
               "target": "$package|$class|$testName"
            }
3. allowDuplication - a boolean value (Default is true)
   * true - will allow duplicate values for the tests in the `testsToRun` parameter
   * false - the opposite

#### Complex Example: 

For this example we will use the same JUnit report, as in our Simple Example, but will change the format of the classname:

    <?xml version="1.0" encoding="utf-8"?>
      <testsuites>
         <testsuite name="suiteName" errors="0" failures="0" skipped="0" tests="3">
            <testcase classname="path.file.className1" name="test1" />
            <testcase classname="path.file.className1" name="test2" />
            <testcase classname="path.file.className2" name="test3" />
         </testsuite>
      </testsuites>

Our framework requires :
* `.mytest` ending for each file 
* the delimitation for the absolute path is \ character.
* the format should be `myframework package:path\file.mytest:class#testName`

In this case the json value for the `testRunnerCustomPattern` parameter:

        {
            "testPattern": "$package:$class#$testName",
            "testDelimiter": ", ",
            "replacements": [
                  {
                     "type": "replaceString",
                     "target": "$class"
                     "string": "."
                     "replacement": "\\"
                  },
                  {
                     "type": "replaceRegex",
                     "target": "$class"
                     "regex": "(\\b\\\\\\b)(?!.*\\1)"
                     "replacement": ".mytest:"
                  }
        }

* the `replaceString` replacement object will substitute each `.` character with \
* the `replaceRegex` will replace the last \ with `.mytest`
!Note: Each escape character in the Json should be doubled. 