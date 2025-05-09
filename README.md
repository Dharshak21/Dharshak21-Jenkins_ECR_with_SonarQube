# Jenkins-AMI

Jenkins offers a simple way to set up a continuous integration and continuous delivery environment for almost any combination of languages and source code repositories.

Prepare Jenkins as AMI under the following conditions.

* Base Image: Ubuntu 22.04
* Processor: 64 Bit x86/AMD
* Default Region: us-east-1
* Price: $0.03/hr 
* Recommended Instance type: T3 Medium 
* Free Trial Period: 5 Days 
* Categories: Infrastructure as Code, Continuous Integration and Continuous Delivery, Application Stacks
* Region Restriction: Available to all regions

Installation Reference:

* An AMI with a fully automated GitHub Actions CI/CD workflow.
* Prepare and list the necessary environment/runtime variables under .sh Obtain a working dashboard and prepare for the demo.
* Scan internally, with AWS Scan, and produce reports. Load the AMI in AWS AMI Repo as Private AMI.
* Prepare PLF/Online Uploader and Push for AWS MP as version v24.01(main version changes on every year,sub version changes on every upgrades).
* Upload the Manual, Description Kit/Usage Instructions, and necessary files to the repository.

Github Actions Workflow: Prepare the Github Actions workflow for Jenkins AMI as follows,

* Build the EC2 Instance in the create-instance Id using PEM key which is provided in github secrets
* SSH into the created EC2 and run the shell script provided for installing the application

