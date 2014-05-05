Contester suite in Scala

Very user-unfriendly at the moment, but works.

- Invoker registry and API
- Engine flows (compile, test, sanitize)
- Polygon client
- Polygon service
- Solution dispatchers

TODO:

- Bidirectional streaming RPC support
    * this seems to be a requirement for dispatcher split. Specifically, mysql
      dispatcher needs to be able to send things to the core dispatcher and
      listen to updates as they go. An option might be to just use queuing for
      that... but there's also at least one use case for dispatcher-invoker
      connection.
- Split main dispatcher vs. db-backed dispatcher.
    * main dispatcher will have a way to accept submits and stream back testing
      results.
    * enough to kickstart, deduplication/state will live in db dispatcher; later
      we may add state to main.
- Refactor module factory to be configurable (protobufs? compiled lambdas?)
- Add http status proxying to invokers
- Add status collection to invokers (bidirectional rpc4)
- Separate invoker registry, dispatcher(s), and sanitizers
  like: invs - registry - dispatchers
                        - sanitizer -^