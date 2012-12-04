Contester suite in Scala

This is the (4th) rewrite of Contester suite. It's available under your
favorite open-source license.

SOON:
- custom test dispatcher/tester
- AMQP publishing of results
- compiled binaries/test output gridfs storage


As of this commit, both dispatcher and server, working together, are able to
take a cxx solution from mysql database and test it. For this to work the
following was implemented:
- MySQL client - on Future
- Sandbox abstraction
- Polygon client
- Problem sanitizing
- Polygon service
- Solution dispatcher
- Rpc4 protocol
- Invoker servers in Go
- Logging/recording of everything

Next steps:
- Error handling everywhere
- GetBinaryType and ntvdm support.
- nt/posix
- Modular dispatcher/school mode
- amqp triggers on input
- amqp notifications on completion
- interactive solution mode

Backburner:
- Polygon service as separate process
