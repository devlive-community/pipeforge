import java.util.zip.ZipFile

def zip = new File(basedir, 'target/greeting-plugin-1.0.0.zip')
assert zip.isFile() : "plugin package missing"
def names = new ZipFile(zip).entries().collect { it.name }
assert names == ['docs/greeting.json', 'icon.svg', 'lib/greeting-plugin-1.0.0.jar', 'lib/snakeyaml-2.3.jar',
                 'native-options/greeting-client-1.x.yaml', 'plugin.yaml'] : names
assert !names.any { it.contains('pipeforge-api') }

def docs = new groovy.json.JsonSlurper().parse(new File(basedir, 'target/site-docs/greeting.json'))
assert docs.id == 'greeting'
assert docs.components[0].identifier == 'greeting'
assert docs.components[0].options*.key == ['count', 'name']
assert docs.components[0].options*.requirement == ['required', 'optional']
assert docs.components[0].options[1].default == 'world'

def classpath = new File(basedir, 'target/pipeforge-plugin/classpath.txt').readLines()
assert classpath[0].startsWith('#') : classpath
assert classpath.size() == 2 : classpath
assert classpath[1].endsWith('snakeyaml-2.3.jar') && new File(classpath[1]).isAbsolute() && new File(classpath[1]).isFile()

def log = new File(basedir, 'build.log').text
assert log.contains('Plugin greeting@1.0.0 is valid (1 component(s))')
assert log.contains('Built plugin package')
assert log.contains('Wrote the development class path')
return true
