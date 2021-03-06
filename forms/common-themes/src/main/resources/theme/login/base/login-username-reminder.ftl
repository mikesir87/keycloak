<#import "template.ftl" as layout>
<@layout.registrationLayout displayInfo=true; section>
    <#if section = "title">
        ${rb.emailUsernameForgotHeader}
    <#elseif section = "header">
        ${rb.emailUsernameForgotHeader}
    <#elseif section = "form">
        <form id="kc-username-reminder-form" class="${properties.kcFormClass!}" action="${url.loginUsernameReminderUrl}" method="post">
            <div class="${properties.kcFormGroupClass!}">
                <div class="${properties.kcLabelWrapperClass!}">
                    <label for="email" class="${properties.kcLabelClass!}">${rb.email}</label>
                </div>
                <div class="${properties.kcInputWrapperClass!}">
                    <input type="text" id="email" name="email" class="${properties.kcInputClass!}" />
                </div>
            </div>

            <div class="${properties.kcFormGroupClass!}">
                <div id="kc-form-options" class="${properties.kcFormOptionsClass!}">
                    <div class="${properties.kcFormOptionsWrapperClass!}">
                        <span><a href="${url.loginUrl}">${rb.backToLogin}</a></span>
                    </div>
                </div>

                <div id="kc-form-buttons" class="${properties.kcFormButtonsClass!}">
                    <input class="btn btn-primary btn-lg" type="submit" value="${rb.submit}"/>
                </div>
            </div>
        </form>
    <#elseif section = "info" >
        ${rb.emailUsernameInstruction}
    </#if>
</@layout.registrationLayout>