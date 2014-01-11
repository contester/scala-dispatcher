Contester suite in Scala

Very user-unfriendly at the moment, but works.

- Invoker registry and API
- Engine flows (compile, test, sanitize)
- Polygon client
- Polygon service
- Solution dispatchers

TODO:
- Refactor module factory to be configurable (protobufs? compiled lambdas?)
- Add http status proxying to invokers
- Add status collection to invokers (bidirectional rpc4)
- Separate invoker registry, dispatcher(s), and sanitizers
  like: invs - registry - dispatchers
                        - sanitizer -^