pipeline {
    agent any
    parameters {
        string(name: 'Git_Hub_URL', description: 'Enter the GitHub URL')
        string(name: 'AWS_Account_Id', description: 'Enter the AWS Account Id')
        string(name: 'MailToRecipients', description: 'Enter the Mail Id for Approval')
        string(name: 'Docker_File_Name', description: 'Enter the Name of Your Dockerfile (Eg:DockerFile)')
        choice(
            choices: ["us-east-1","us-east-2","us-west-1","us-west-2","ap-south-1","ap-northeast-3","ap-northeast-2","ap-southeast-1","ap-southeast-2","ap-northeast-1","ca-central-1","eu-central-1","eu-west-1","eu-west-2","eu-west-3","eu-north-1","sa-east-1"],
            description: 'Select your Region Name (eg: us-east-1). To Know your region code refer URL "https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/Concepts.RegionsAndAvailabilityZones.html#Concepts.RegionsAndAvailabilityZones.Regions"',
            name: 'Region_Name'
        )
        string(name: 'ECR_Repo_Name', defaultValue: 'ecr_default_repo', description: 'ECR Repositary (Default: ecr_default_repo)')
        string(name: 'Version_Number', defaultValue: '1.0', description: 'Enter the Version Number for ECR Image (Default: 1.0)')
        string(name: 'Workspace_name', defaultValue: 'Jenkins_ECR_with_SonarQube_CodeTest', description: 'Workspace name')
        string(name: 'AWS_Credentials_Id', defaultValue: 'AWS_Credentials', description: 'AWS Credentials Id')
        string(name: 'Git_Credentials_Id', defaultValue: 'Github_Credentials', description: 'Git Credentials Id')
        string(name: 'SONAR_PROJECT_NAME', defaultValue: 'SonarScannerCheck', description: 'Sonar Project Name (Default: SonarScannerCheck)')
    }
    environment {
        ECR_Credentials = "ecr:${Region_Name}:AWS_Credentials"
        S3_Url = 'https://yamlclusterecs1.s3.amazonaws.com/master.yaml'
    }
    stages {
        stage('Clone the Git Repository') {
            steps {
                git branch: 'main', credentialsId: "${Git_Credentials_Id}", url: "${Git_Hub_URL}"
            }
        }
        stage('Docker start') {
            steps {
                sh '''
                docker ps
                docker start sonarqube
                docker start zaproxy
                curl ipinfo.io/ip > ip.txt
                '''
            }
        }
        stage('Wait for SonarQube to Start') {
            steps {
                script {
                    sleep 120
                }
            }
        }
        stage('Send Sonar Analysis Report and Approval Email for Build Image') {
            steps {
                script {
                    def Jenkins_IP = sh(
                        returnStdout: true,
                        script: 'cat ip.txt'
                    ).trim()
                    emailext(
                        subject: "Approval Needed to Build Docker Image",
                        body: """SonarQube Analysis Report URL: http://${Jenkins_IP}:9000/dashboard?id=${SONAR_PROJECT_NAME}<br>
                                 Username: admin<br>Password: 12345<br>
                                 Please Approve to Build the Docker Image in Testing Environment<br><br>
                                 <a href="${BUILD_URL}input/">Click to Approve</a>""",
                        mimeType: 'text/html',
                        recipientProviders: [[$class: 'CulpritsRecipientProvider'], [$class: 'RequesterRecipientProvider']],
                        from: "dharshak214@gmail.com",
                        to: "${MailToRecipients}"
                    )
                }
            }
        }
        stage('Approval-Build Image') {
            steps {
                timeout(time: 30, unit: 'MINUTES') {
                    input message: 'Please approve the build image process by clicking the link provided in the email.', ok: 'Proceed'
                }
            }
        }
        stage('Create a ECR Repository') {
            steps {
                withCredentials([[ 
                    $class: 'AmazonWebServicesCredentialsBinding', 
                    credentialsId: "${AWS_Credentials_Id}", 
                    accessKeyVariable: 'AWS_ACCESS_KEY_ID', 
                    secretKeyVariable: 'AWS_SECRET_ACCESS_KEY' 
                ]]) {
                    sh '''
                    # Create ECR repository if it doesn't exist
                    aws ecr create-repository --repository-name ${ECR_Repo_Name} --region ${Region_Name} || true
                    cd /var/lib/jenkins/workspace/${Workspace_name}
                    '''
                }
            }
        }
     stage('Build and Push the Docker Image to ECR Repository') {
    steps {
        withCredentials([
            [$class: 'AmazonWebServicesCredentialsBinding', 
             credentialsId: "${AWS_Credentials_Id}", 
             accessKeyVariable: 'AWS_ACCESS_KEY_ID', 
             secretKeyVariable: 'AWS_SECRET_ACCESS_KEY']
        ]) {
            script {
    echo "DEBUG VALUES:"
    echo "AWS_Account_Id: ${params.AWS_Account_Id}"
    echo "Region_Name: ${params.Region_Name}"
    echo "ECR_Repo_Name: ${params.ECR_Repo_Name}"
    echo "Version_Number: ${params.Version_Number}"
    def imageName = "${params.AWS_Account_Id}.dkr.ecr.${params.Region_Name}.amazonaws.com/${params.ECR_Repo_Name}:${params.Version_Number}"
    echo " Docker image name will be: ${imageName}"
    def DockerfilePath = sh(script: "find -name ${params.Docker_File_Name}", returnStdout: true).trim()
    echo "FOUND Dockerfile at: ${DockerfilePath}"    
    sh """
        aws ecr get-login-password --region ${params.Region_Name} | docker login --username AWS --password-stdin ${params.AWS_Account_Id}.dkr.ecr.${params.Region_Name}.amazonaws.com
        docker build -t ${imageName} -f ${DockerfilePath} .
        docker push ${imageName}
    """
}
        }
    }
}
    }
    post {
        always {
            emailext(
                subject: "Jenkins Build: ${currentBuild.currentResult} - ${env.JOB_NAME} #${env.BUILD_NUMBER}",
                body: """
                    <p><strong>Build Result:</strong> ${currentBuild.currentResult}</p>
                    <p><strong>Job:</strong> ${env.JOB_NAME}</p>
                    <p><strong>Build Number:</strong> ${env.BUILD_NUMBER}</p>
                    <p><strong>Build URL:</strong> <a href="${env.BUILD_URL}">${env.BUILD_URL}</a></p>
                """,
                mimeType: 'text/html',
                to: "${MailToRecipients}",
                from: "dharshak214@gmail.com"
            )
        }
    }
}
