Storage:
- problems and problem data
- submits and submit data (supplied by the user)
- testings and testing data (every redjudge will generate it)
...
- modules (moduleType/sourceChecksum -> content)

Caching:
- compilation results (moduleType/sourceChecksum/compilerVersion -> (protobuf, module))
- ? execution results (moduleType/checksum + inputChecksum -> protobuf)
- checking results (inputChecksum + testId -> protobuf) - >first<
- results for the entire test (moduleType/sourceChecksum/compiledVersion + testId -> protobuf)