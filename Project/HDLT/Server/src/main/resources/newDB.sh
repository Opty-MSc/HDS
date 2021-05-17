#!/bin/sh
ServerName=$1
DBName="${ServerName}hdltdb"
DBUser="${ServerName}"
DBPwd="wJSBj8pbNDu57yJW${ServerName}"

CMD="
dropdb --if-exists ${DBName};
createdb ${DBName};
echo \"DROP ROLE IF EXISTS ${DBUser}; CREATE ROLE ${DBUser} LOGIN PASSWORD '${DBPwd}';\" | psql ${DBName};
"

sudo service postgresql start
echo "${CMD}" | sudo su -l postgres
