clean:
	mvn clean
	docker-compose down

build:
	mvn package \
		&& docker build -t pierredavidbelanger/zdlb:snapshot .

run:
	mvn package \
		&& docker-compose up --build

deploy: clean build
	version=$$(mvn -q -Dexec.executable='echo' -Dexec.args='$${project.version}' --non-recursive org.codehaus.mojo:exec-maven-plugin:1.3.1:exec 2> /dev/null) \
		&& docker tag pierredavidbelanger/zdlb:snapshot pierredavidbelanger/zdlb:$$version \
		&& docker push pierredavidbelanger/zdlb:$$version

.PHONY: build