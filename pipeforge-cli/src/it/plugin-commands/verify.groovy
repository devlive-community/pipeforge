/*
 * Copyright (c) 2026 devlive-community/pipeforge
 *
 * Licensed under the MIT License. See the LICENSE file in the
 * project root for full license text.
 */

// Runs pipeforge plugin validate, test and docs on the project the invoker just built. The nested
// Maven builds use the invoker repository offline, with the repository id of the invoker settings
// (local.central), under which the invoker build cached what it resolved.

def settings = new File(basedir, 'it-settings.xml')
settings.text = """<settings><localRepository>${localRepositoryPath}</localRepository><profiles><profile><id>user</id>
<activation><activeByDefault>true</activeByDefault></activation>
<repositories><repository><id>local.central</id><url>${userRepositoryUrl}</url></repository></repositories>
<pluginRepositories><pluginRepository><id>local.central</id><url>${userRepositoryUrl}</url></pluginRepository></pluginRepositories>
</profile></profiles></settings>"""

def cli = { String... args ->
    def classpath = cliClasses + File.pathSeparator + new File(cliClasspathFile).text.trim()
    def command = [javaExecutable, '-cp', classpath, 'org.devlive.pipeforge.cli.Main'] + args.toList()
    def builder = new ProcessBuilder(command).directory(basedir).redirectErrorStream(false)
    builder.environment().put('PIPEFORGE_LANG', 'en-US')
    builder.environment().put('MAVEN_ARGS', "-s ${settings} -o".toString())
    def process = builder.start()
    def out = new StringBuilder(), err = new StringBuilder()
    process.consumeProcessOutput(out, err)
    process.waitFor()
    println "pipeforge ${args.join(' ')} -> ${process.exitValue()}\n${out}\n${err}"
    [exit: process.exitValue(), out: out.toString(), err: err.toString()]
}

def zip = new File(basedir, 'target/upper-plugin-1.0.0.zip')
assert zip.isFile()

def validate = cli('plugin', 'validate', zip.path)
assert validate.exit == 0
assert validate.out.contains('upper@1.0.0')
assert validate.out.contains('  OK  components')
assert validate.out.contains('  transform upper, capabilities: -')

def test = cli('plugin', 'test', '.', '--groups', 'options,transform', '--maven', mavenExecutable)
assert test.exit == 0
assert test.out ==~ /Tests passed: \d+ run, 0 failed, 0 errors, \d+ skipped\n/
assert test.err.contains('BUILD SUCCESS')

def missing = cli('plugin', 'test', '.', '--groups', 'resume', '--maven', mavenExecutable)
assert missing.exit == 4
assert missing.err.contains("'resume' in pipeforge.testkit.groups is not a group of this component")

def check = cli('plugin', 'docs', '.', '--check', '--output-dir', 'target/pipeforge-plugin/docs', '--maven', mavenExecutable)
assert check.exit == 0
assert check.out.startsWith('target/pipeforge-plugin/docs/upper.json is current')

def docs = cli('plugin', 'docs', '.', '--maven', mavenExecutable)
assert docs.exit == 0
assert docs.out.contains('transform upper  column')
assert new File(basedir, 'src/generated/plugin-docs/upper.json').isFile()
return true
