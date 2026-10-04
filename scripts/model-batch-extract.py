"""Build-only extraction of the unchanged private Android provider contract.
Fail closed on any source change: a new fingerprint requires reviewed extraction.
"""
import hashlib
import pathlib
import sys

EXPECTED = "09c725ac34c91079f2b031d64f538cdcd85e58789e2224618b59c17dff8ccae3"
source_path = pathlib.Path(sys.argv[1])
source = source_path.read_bytes()
assert hashlib.sha256(source).hexdigest() == EXPECTED, "Provider changed; review the extraction before running a new batch"
fixtures = source_path.parents[6] / "runtimeEvaluation/java/jp/n624/takupoke/android/LiteRtEvaluationFixtures.kt"
# The shared corpus is frozen alongside the private Provider contract.
assert hashlib.sha256(fixtures.read_bytes()).hexdigest() == "64a6abcc0d3789170a3b6a59b6247b0787127c391b4188fd133c322f11c21c4f", "Fixture changed; review the common corpus"
text = source.decode()
def between(start, end):
    assert text.count(start) == 1 and text.count(end) == 1
    return text.split(start, 1)[1].split(end, 1)[0]
classes = between("@Serializable private data class GeneratedRecoveryLesson", "/** Constructed")
classes = "@Serializable private data class GeneratedRecoveryLesson" + classes
instruction = between("val instruction = ", "\n        requireNotNull(engine)")
strict = between("    private fun rejectMalformedJson", "    private fun schema")
schema = between("    private fun schema", "    override fun close")
parse = between("                require(text.length <= 16384)", "\n            } catch (e: CancellationException)")
parse = "require(text.length <= 16384)" + parse
parse = parse.replace("generated.lessons.map", "return generated.lessons.map")
output = pathlib.Path(sys.argv[2]); output.mkdir(parents=True, exist_ok=True)
(output / "ProviderContract.kt").write_text("package jp.n624.takupoke.evaluation\nimport jp.n624.takupoke.core.*\nimport kotlinx.serialization.Serializable\n" + classes + "\nobject ProviderContract {\n const val fingerprint = \"" + EXPECTED + "\"\n fun instruction(cell: RecoveryPromptCell): String = " + instruction + "\n fun parse(text:String,cell:RecoveryPromptCell):List<RecoveryLesson> {\n" + parse + "\n }\n private fun rejectMalformedJson" + strict + " fun schema" + schema + "\n}\n")
