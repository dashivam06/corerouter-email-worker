package com.fleebug.config;

import com.azure.communication.email.EmailClient;
import com.azure.communication.email.EmailClientBuilder;
import com.azure.core.credential.AzureKeyCredential;

public class MailConfig {


    private static EmailClient emailClient;

    // private static String connectionString = System.getenv("AZURE_COMMUNICATION_COREROUTER_STRING");

    private static String endpoint = System.getenv("AZURE_COMMUNICATION_COREROUTER_ENDPOINT");

    private static String accessKey = System.getenv("AZURE_COMMUNICATION_COREROUTER_ACCESS_KEY");

    static
    {

        if(endpoint == null || endpoint.isEmpty() || accessKey == null || accessKey.isEmpty())
        {
            throw new IllegalStateException("Azure Communication Service endpoint or access key is not set in environment variables.");
        }

        AzureKeyCredential credential = new AzureKeyCredential(accessKey);

        emailClient = new EmailClientBuilder()
                                    .endpoint(endpoint)
                                    .credential(credential)
                                    .buildClient();
        

        /*  This is an alternative way to initialize the EmailClient using connection string, 
            but we are using endpoint and credential for better security practices.
        */ 

        // emailClient = new EmailClientBuilder().connectionString(connectionString).buildClient();   

    }



    public static EmailClient getEmailClient() {
        return emailClient;
    }




    
}
