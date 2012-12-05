package org.stingray.contester

import org.stingray.contester.proto.Contester._

class CompilationFailure(val result: Compilation) extends Throwable(result.toString)
class CompilationGeneralFailure(reason: String) extends Throwable(reason)

