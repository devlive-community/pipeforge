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

def log = new File(basedir, 'build.log').text
assert log.contains('Plugin greeting@1.0.0 is valid (1 component(s))')
assert log.contains('Built plugin package')
return true
