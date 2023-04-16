source ./env.sh
initEnv
set -x

$SL_SCRIPT --jdbc-mapping ddl2yml-alltables.yml --output-dir . --yml-template domain-template.comet.yml

echo "Resulting file available in public.comet.yml"

