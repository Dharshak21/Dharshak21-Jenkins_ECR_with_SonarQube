#!/bin/bash
sudo apt update -y && sudo apt upgrade -y

sudo apt install openjdk-17-jre -y

curl -fsSL https://pkg.jenkins.io/debian-stable/jenkins.io-2023.key | sudo tee /usr/share/keyrings/jenkins-keyring.asc > /dev/null
echo deb [signed-by=/usr/share/keyrings/jenkins-keyring.asc] https://pkg.jenkins.io/debian-stable binary/ | sudo tee /etc/apt/sources.list.d/jenkins.list > /dev/null
sudo apt update -y
sudo apt install docker.io -y
sudo chmod 666 /var/run/docker.sock
sudo apt install jenkins -y
sudo curl "https://awscli.amazonaws.com/awscli-exe-linux-x86_64-2.0.30.zip" -o "awscliv2.zip"
sudo apt-get install unzip
sudo unzip awscliv2.zip
sudo ./aws/install
sudo systemctl enable jenkins
sudo systemctl start jenkins
sudo systemctl status jenkins
sudo cat /var/lib/jenkins/secrets/initialAdminPassword
sudo docker pull sonarqube
sudo docker run -d --name sonarqube -p 9000:9000 sonarqube 
sudo docker pull owasp/zap2docker-stable
sudo docker run -dt --name owasp owasp/zap2docker-stable /bin/bash
sudo docker exec owasp mkdir /zap/wrk

