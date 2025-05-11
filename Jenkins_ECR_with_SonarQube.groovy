pipeline {
    agent any
    parameters {
        string(name: 'Git_Hub_URL', description: 'Enter the GitHub URL')
        string(name: 'AWS_Account_Id', description: 'Enter the AWS Account Id')
        string(name: 'MailToRecipients', description: 'Enter the Mail Id for Approval')
        string(name: 'Docker_File_Name', description: 'Enter the Name of Your Dockerfile (Eg:Dockerfile)')
        choice(
            choices: ["us-east-1","us-east-2","us-west-1","us-west-2","ap-south-1","ap-northeast-3","ap-northeast-2","ap-southeast-1","ap-southeast-2","ap-northeast-1","ca-central-1","eu-central-1","eu-west-1","eu-west-2","eu-west-3","eu-north-1","sa-east-1"],
            description: 'Select your Region Name (eg: us-east-1)',
            name: 'Region_Name'
        )
        string(name: 'ECR_Repo_Name', defaultValue: 'ecr_default_repo', description: 'ECR Repository (Default: ecr_default_repo)')
        string(name: 'Version_Number', defaultValue: '1.0', description: 'Enter the Version Number for ECR Image (Default: 1.0)')
        string(name: 'Workspace_name', defaultValue: 'Jenkins_ECR_with_SonarQube_CodeTest', description: 'Workspace name')
        string(name: 'AWS_Credentials_Id', defaultValue: 'AWS_Credentials', description: 'AWS Credentials Id')
        string(name: 'Git_Credentials_Id', defaultValue: 'Github_Credentials', description: 'Git Credentials Id')
        string(name: 'SONAR_PROJECT_NAME', defaultValue: 'SonarScannerCheck', description: 'Sonar Project Name')
    }
    environment {
        S3_Url = 'https://yamlclusterecs1.s3.amazonaws.com/master.yaml'
    }
    stages {
        stage('Clone the Git Repository') {
            steps {
                git branch: 'main', credentialsId: "${params.Git_Credentials_Id}", url: "${params.Git_Hub_URL}"
            }
        }

        stage('Docker start') {
            steps {
                sh '''
                docker ps
                docker start sonarqube || true
                docker start zaproxy || true
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

        stage('SonarQube Analysis') {
            steps {
                withSonarQubeEnv('SonarQube') {
                    sh """
                    sonar-scanner \
                    -Dsonar.projectKey=${params.SONAR_PROJECT_NAME} \
                    -Dsonar.sources=. \
                    -Dsonar.host.url=http://localhost:9000 \
                    -Dsonar.login=admin \
                    -Dsonar.password=12345
                    """
                }
            }
        }

        stage('Send Approval Email') {
            steps {
                script {
                    def Jenkins_IP = sh(script: 'cat ip.txt', returnStdout: true).trim()
                    emailext(
                        subject: "Approval Needed to Build Docker Image",
                        body: """SonarQube Analysis Report URL: http://${Jenkins_IP}:9000/dashboard?id=${params.SONAR_PROJECT_NAME}<br>
                                 Username: admin<br>Password: 12345<br><br>
                                 <a href="${env.BUILD_URL}input/">Click to Approve</a>""",
                        mimeType: 'text/html',
                        to: "${params.MailToRecipients}",
                        from: "dharsha214@gmail.com"
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

        stage('Create ECR Repository') {
            steps {
                withCredentials([[
                    $class: 'AmazonWebServicesCredentialsBinding',
                    credentialsId: "${params.AWS_Credentials_Id}",
                    accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                    secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                ]]) {
                    sh '''
                    aws ecr create-repository --repository-name "$ECR_Repo_Name" --region "$Region_Name" || true
                    '''
                }
            }
        }

        stage('Build and Push Docker Image to ECR') {
            steps {
                withCredentials([[
                    $class: 'AmazonWebServicesCredentialsBinding',
                    credentialsId: "${params.AWS_Credentials_Id}",
                    accessKeyVariable: 'AWS_ACCESS_KEY_ID',
                    secretKeyVariable: 'AWS_SECRET_ACCESS_KEY'
                ]]) {
                    script {
                        def dockerfilePath = sh(script: "find . -name ${params.Docker_File_Name}", returnStdout: true).trim()
                        dockerfilePath = dockerfilePath.replaceAll('^\\./', '')
                        sh """
                            aws ecr get-login-password --region ${params.Region_Name} | docker login --username AWS --password-stdin ${params.AWS_Account_Id}.dkr.ecr.${params.Region_Name}.amazonaws.com
                            docker build . -t ${params.ECR_Repo_Name} -f ${dockerfilePath}
                            docker tag ${params.ECR_Repo_Name}:latest ${params.AWS_Account_Id}.dkr.ecr.${params.Region_Name}.amazonaws.com/${params.ECR_Repo_Name}:${params.Version_Number}
                            docker push ${params.AWS_Account_Id}.dkr.ecr.${params.Region_Name}.amazonaws.com/${params.ECR_Repo_Name}:${params.Version_Number}
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
                to: "${params.MailToRecipients}",
                from: "dharsha214@gmail.com"
            )
        }
    }
}
