/*
 * Password Management Servlets (PWM)
 * http://code.google.com/p/pwm/
 *
 * Copyright (c) 2006-2009 Novell, Inc.
 * Copyright (c) 2009-2010 The PWM Project
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package password.pwm.servlet;

import com.novell.ldapchai.ChaiFactory;
import com.novell.ldapchai.ChaiUser;
import com.novell.ldapchai.exception.ChaiOperationException;
import com.novell.ldapchai.exception.ChaiUnavailableException;
import com.novell.ldapchai.provider.ChaiProvider;
import com.novell.ldapchai.util.SearchHelper;
import password.pwm.*;
import password.pwm.bean.EmailItemBean;
import password.pwm.bean.GuestUpdateServletBean;
import password.pwm.bean.SessionStateBean;
import password.pwm.bean.UserInfoBean;
import password.pwm.config.Configuration;
import password.pwm.config.FormConfiguration;
import password.pwm.config.Message;
import password.pwm.config.PwmSetting;
import password.pwm.error.*;
import password.pwm.util.Helper;
import password.pwm.util.IntruderManager;
import password.pwm.util.PwmLogger;
import password.pwm.util.ServletHelper;
import password.pwm.util.stats.Statistic;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Servlet for updating guest users (helpdesk/admin registration)
 *
 * @author Jason D. Rivard, Menno Pieters
 */
public class GuestUpdateServlet extends TopServlet {
// ------------------------------ FIELDS ------------------------------

    private static final PwmLogger LOGGER = PwmLogger.getLogger(GuestUpdateServlet.class);

    protected void processRequest(
            final HttpServletRequest req,
            final HttpServletResponse resp
    )
            throws ServletException, ChaiUnavailableException, IOException, PwmUnrecoverableException, NumberFormatException
    {
        //Fetch the session state bean.
        final PwmSession pwmSession = PwmSession.getPwmSession(req);
        final SessionStateBean ssBean = pwmSession.getSessionStateBean();

        if (!ssBean.isAuthenticated()) {
            ssBean.setSessionError(new ErrorInformation(PwmError.ERROR_AUTHENTICATION_REQUIRED));
            ServletHelper.forwardToErrorPage(req, resp, this.getServletContext());
            return;
        }
        
        if (!Permission.checkPermission(Permission.GUEST_REGISTRATION, pwmSession)) {
            ssBean.setSessionError(new ErrorInformation(PwmError.ERROR_UNAUTHORIZED));
            ServletHelper.forwardToErrorPage(req, resp, this.getServletContext());
            return;
        }
        
        final String actionParam = Validator.readStringFromRequest(req, PwmConstants.PARAM_ACTION_REQUEST, 255);
        final Configuration config = pwmSession.getConfig();

        if (!config.readSettingAsBoolean(PwmSetting.GUEST_ENABLE)) {
            ssBean.setSessionError(PwmError.ERROR_SERVICE_NOT_AVAILABLE.toInfo());
            ServletHelper.forwardToErrorPage(req, resp, this.getServletContext());
            return;
        }

        if (actionParam != null && actionParam.length() > 0) {
        	if (actionParam.equalsIgnoreCase("search")) {
        		this.processSearch(req, resp);
        		return;
        	} else if (actionParam.equalsIgnoreCase("update")) {
        		this.processUpdate(req, resp);
        		return;
        	}
        }
        LOGGER.trace("No parameters, initiate search");
        this.forwardToSearchJSP(req, resp);
    }

    protected void processSearch(
            final HttpServletRequest req,
            final HttpServletResponse resp
    ) throws ServletException, ChaiUnavailableException, IOException, PwmUnrecoverableException {
        final PwmSession pwmSession = PwmSession.getPwmSession(req);
        final Configuration config = pwmSession.getConfig();
        final ContextManager theManager = pwmSession.getContextManager();
        final String namingAttribute = config.readSettingAsString(PwmSetting.LDAP_NAMING_ATTRIBUTE);
        final String usernameParam = Validator.readStringFromRequest(req, "username", 256);
        final String searchContext = config.readSettingAsString(PwmSetting.GUEST_CONTEXT);
        final SessionStateBean ssBean = pwmSession.getSessionStateBean();
        final GuestUpdateServletBean guBean = pwmSession.getGuestUpdateServletBean();

        try {
            final ChaiProvider provider = pwmSession.getSessionManager().getChaiProvider();
            final Map<String, String> filterClauses = new HashMap<String, String>();
            filterClauses.put(namingAttribute, usernameParam);
            final SearchHelper searchHelper = new SearchHelper();
            searchHelper.setFilterAnd(filterClauses);
            
            final Set<String> resultDNs = new HashSet<String>(provider.search(searchContext, searchHelper).keySet());
            if (resultDNs.size() > 1) {
                final ErrorInformation error = new ErrorInformation(PwmError.ERROR_MULTI_USERNAME, null, usernameParam);
				ssBean.setSessionError(error);
            	this.forwardToSearchJSP(req, resp);
            	return;
            }
            if (resultDNs.size() == 0) {
                final ErrorInformation error = new ErrorInformation(PwmError.ERROR_CANT_MATCH_USER, null, usernameParam);
				ssBean.setSessionError(error);
            	this.forwardToSearchJSP(req, resp);
            	return;
            }
            final String userDN = resultDNs.iterator().next();
            guBean.setUpdateUserDN(userDN);
            final ChaiUser theGuest = ChaiFactory.createChaiUser(userDN, provider);
        	final Properties formProps = pwmSession.getSessionStateBean().getLastParameterValues();
        	try {
                final Map<String, FormConfiguration> updateParams = guBean.getUpdateParams();
                final Collection<String> involvedAttrs = new HashSet<String>(updateParams.keySet());
                involvedAttrs.remove("__accountDuration__");
                final Properties userAttrValues = provider.readStringAttributes(userDN, involvedAttrs.toArray(new String[involvedAttrs.size()]));
        		final String adminDnAttribute = config.readSettingAsString(PwmSetting.GUEST_ADMIN_ATTRIBUTE);
        		final Boolean origAdminOnly = config.readSettingAsBoolean(PwmSetting.GUEST_EDIT_ORIG_ADMIN_ONLY);
        		if (origAdminOnly && adminDnAttribute != null && adminDnAttribute.length() > 0) { 
        			final String origAdminDn = userAttrValues.getProperty(adminDnAttribute);
        			if (origAdminDn != null && origAdminDn.length() > 0) {
        			    if (!pwmSession.getUserInfoBean().getUserDN().equalsIgnoreCase(origAdminDn)) {
        			        final ErrorInformation info = new ErrorInformation(PwmError.ERROR_ORIG_ADMIN_ONLY);
                            ssBean.setSessionError(info);
                            LOGGER.warn(pwmSession, info);
                            forwardToSearchJSP(req, resp);
        			    }
        			}
        		}
                final String expirationAttribute = config.readSettingAsString(PwmSetting.GUEST_EXPIRATION_ATTRIBUTE);
	    		final Date expiration = theGuest.readDateAttribute(expirationAttribute);
		    	if (expiration != null) {
                    guBean.setRemainingDays(expiration);
                }
                
                for (final String key : updateParams.keySet()) {
                    final String value = userAttrValues.getProperty(key);
                    if (value != null) {
                        formProps.setProperty(key, value);
                    }
                }
                formProps.setProperty("__accountDuration__", guBean.getMaximumDuration().toString());
            } catch (ChaiOperationException e) {
                LOGGER.warn(pwmSession, "error reading current attributes for user: " + e.getMessage());
            }
        } catch (ChaiOperationException e) {
            final ErrorInformation info = new ErrorInformation(PwmError.ERROR_UNKNOWN, "error searching for guest user: " + e.getMessage());
            ssBean.setSessionError(info);
            LOGGER.warn(pwmSession, info);
            this.forwardToJSP(req, resp);
            return;
        }
		this.forwardToJSP(req, resp);
    }
    
    protected void processUpdate(
            final HttpServletRequest req,
            final HttpServletResponse resp
    ) throws ServletException, ChaiUnavailableException, IOException, PwmUnrecoverableException, NumberFormatException {
        //Fetch the session state bean.
        final PwmSession pwmSession = PwmSession.getPwmSession(req);
        final SessionStateBean ssBean = pwmSession.getSessionStateBean();
        final GuestUpdateServletBean guBean = pwmSession.getGuestUpdateServletBean();
        final Locale locale = Locale.getDefault();
        String durationString = null;
        Properties notifyAttrs = new Properties();
        final IntruderManager intruderMgr = pwmSession.getContextManager().getIntruderManager();
        final Configuration config = pwmSession.getConfig();
        
        Map<String, FormConfiguration> updateParams = guBean.getUpdateParams();
        final String expirationAttribute = config.readSettingAsString(PwmSetting.GUEST_EXPIRATION_ATTRIBUTE);

        Validator.validatePwmFormID(req);

        //read the values from the request
        try {
            Validator.updateParamValues(pwmSession, req, updateParams);
        } catch (PwmDataValidationException e) {
            ssBean.setSessionError(e.getErrorInformation());
            this.forwardToJSP(req, resp);
            return;
        }
        
        // see if the values meet form requirements.
        try {
            Validator.validateParmValuesMeetRequirements(updateParams, pwmSession);
            if (expirationAttribute != null && expirationAttribute.length() > 0) {
                durationString = updateParams.get("__accountDuration__").getValue();
   	            final Integer maxDuration = Integer.parseInt(config.readSettingAsString(PwmSetting.GUEST_MAX_VALID_DAYS));
                Validator.validateNumericString(durationString, 0, maxDuration, pwmSession);
            }
        } catch (NumberFormatException e) {
            ssBean.setSessionError(new ErrorInformation(PwmError.CONFIG_FORMAT_ERROR,null));
            intruderMgr.addBadAddressAttempt(pwmSession);
            this.forwardToJSP(req, resp);
            return;
        } catch (PwmDataValidationException e) {
            ssBean.setSessionError(e.getErrorInformation());
            intruderMgr.addBadAddressAttempt(pwmSession);
            this.forwardToJSP(req, resp);
            return;
        }

        // check unique fields against ldap
        for (final String attr : config.readStringArraySetting(PwmSetting.GUEST_UNIQUE_ATTRIBUTES)) {
        	LOGGER.trace("ATTR: "+attr);
            final FormConfiguration paramConfig = updateParams.get(attr);
            try {
            	if (!attr.equalsIgnoreCase(config.readSettingAsString(PwmSetting.LDAP_NAMING_ATTRIBUTE))) {
	                validateAttributeUniqueness(pwmSession, paramConfig, guBean.getUpdateUserDN());
            	}
            } catch (PwmDataValidationException e) {
                ssBean.setSessionError(e.getErrorInformation());
                intruderMgr.addBadAddressAttempt(pwmSession);
                this.forwardToJSP(req, resp);
                return;
            }
        }

        //update user
        try {
            final ChaiProvider provider = pwmSession.getSessionManager().getChaiProvider();
            final Map<String, String> updateAttrs = new HashMap<String, String>();

            for (final String key : updateParams.keySet()) {
               	if (!key.equalsIgnoreCase("__accountDuration__")) { 
                   	final FormConfiguration formConfig = updateParams.get(key);
                   	LOGGER.trace("DEBUG: Attribute: " + key);
                   	updateAttrs.put(formConfig.getAttributeName(), formConfig.getValue());
                   	notifyAttrs.put(formConfig.getAttributeName(), formConfig.getValue());
             	}
            }

            final ChaiUser theGuest = ChaiFactory.createChaiUser(guBean.getUpdateUserDN(), pwmSession.getSessionManager().getChaiProvider());
			GregorianCalendar cal = null;
			String expirationDate = null;
	
			// If an expiration date attribute is available, calculate the expiration date and convert it to
			//   Greenwich Mean Time.
			if (expirationAttribute != null && expirationAttribute.length() > 0 && durationString != null) {
				cal = new GregorianCalendar(locale);
				cal.set(Calendar.HOUR_OF_DAY,0);
				cal.set(Calendar.MINUTE,0);
				cal.set(Calendar.SECOND,0);
				cal.set(Calendar.MILLISECOND,0);
				cal.add(Calendar.DAY_OF_MONTH,Integer.parseInt(durationString));
				TimeZone tz = TimeZone.getTimeZone("GMT");
				cal.setTimeZone(tz);
				SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMddHHmmss'Z'");
				expirationDate = fmt.format(cal.getTime());
				SimpleDateFormat nfmt = new SimpleDateFormat();
				notifyAttrs.put(expirationAttribute, nfmt.format(cal.getTime()));
				LOGGER.trace("DEBUG: "+expirationAttribute+"="+expirationDate+" ("+nfmt.format(cal.getTime())+")");
			}

            if (expirationDate != null) {
               	updateAttrs.put(expirationAttribute, expirationDate);
            }
            // Update user attributes
            Helper.writeMapToEdir(pwmSession, theGuest, updateAttrs);
        } catch (PwmOperationalException e) {
            final ErrorInformation info = new ErrorInformation(PwmError.ERROR_UNKNOWN, "unexpected error writing to ldap: " + e.getMessage());
            LOGGER.warn(pwmSession, info, e);
            ssBean.setSessionError(info);
            ServletHelper.forwardToErrorPage(req, resp, this.getServletContext());
            return;
        }

        //everything good so forward to confirmation page.
        this.sendUpdateGuestEmailConfirmation(pwmSession, notifyAttrs);
        ssBean.setSessionSuccess(Message.SUCCESS_UPDATE_GUEST, null);

        pwmSession.getContextManager().getStatisticsManager().incrementValue(Statistic.UPDATED_GUESTS);
        ServletHelper.forwardToSuccessPage(req, resp, this.getServletContext());
        return;
    }

    public static void validateAttributeUniqueness(
            final PwmSession pwmSession,
            final FormConfiguration paramConfig,
            final String userDN
    )
            throws PwmDataValidationException, ChaiUnavailableException
    {
        try {
            final ChaiProvider provider = pwmSession.getContextManager().getProxyChaiProvider();

            final Map<String, String> filterClauses = new HashMap<String, String>();
            //filterClauses.put(ChaiConstant.ATTR_LDAP_OBJECTCLASS, ChaiConstant.OBJECTCLASS_BASE_LDAP_USER);
            filterClauses.put(paramConfig.getAttributeName(), paramConfig.getValue());

            final SearchHelper searchHelper = new SearchHelper();
            searchHelper.setFilterAnd(filterClauses);

            final Set<String> resultDNs = new HashSet<String>(provider.search(pwmSession.getConfig().readSettingAsString(PwmSetting.LDAP_CONTEXTLESS_ROOT), searchHelper).keySet());

            // remove the user DN from the result set.
            resultDNs.remove(userDN);

            if (resultDNs.size() > 0) {
                final ErrorInformation error = new ErrorInformation(PwmError.ERROR_FIELD_DUPLICATE, null, paramConfig.getLabel());
                throw new PwmDataValidationException(error);
            }
        } catch (ChaiOperationException e) {
            LOGGER.debug(e);
        }
    }

    private void sendUpdateGuestEmailConfirmation(final PwmSession pwmSession, final Properties attrs) {
        final ContextManager theManager = pwmSession.getContextManager();
        final UserInfoBean userInfoBean = pwmSession.getUserInfoBean();
        final Configuration config = pwmSession.getConfig();
        final Locale locale = pwmSession.getSessionStateBean().getLocale();

        final String fromAddress = config.readLocalizedStringSetting(PwmSetting.EMAIL_UPDATEGUEST_FROM,locale);
        final String subject = config.readLocalizedStringSetting(PwmSetting.EMAIL_UPDATEGUEST_SUBJECT,locale);
        final String plainBody = Helper.replaceAllPatterns(config.readLocalizedStringSetting(PwmSetting.EMAIL_UPDATEGUEST_BODY,locale), attrs);
        final String htmlBody = Helper.replaceAllPatterns(config.readLocalizedStringSetting(PwmSetting.EMAIL_UPDATEGUEST_BODY_HTML,locale), attrs);

        final String toAddress = attrs.getProperty(config.readSettingAsString(PwmSetting.EMAIL_USER_MAIL_ATTRIBUTE));
        if (toAddress == null || toAddress.length() < 1) {
            LOGGER.debug(pwmSession, "unable to send updated guest user email: no email configured");
            return;
        }

        theManager.sendEmailUsingQueue(new EmailItemBean(toAddress, fromAddress, subject, plainBody, htmlBody));
    }

    private void forwardToJSP(
            final HttpServletRequest req,
            final HttpServletResponse resp
    )
            throws IOException, ServletException {
        this.getServletContext().getRequestDispatcher('/' + PwmConstants.URL_JSP_GUEST_UPDATE).forward(req, resp);
    }

    private void forwardToSearchJSP(
            final HttpServletRequest req,
            final HttpServletResponse resp
    )
            throws IOException, ServletException {
        this.getServletContext().getRequestDispatcher('/' + PwmConstants.URL_JSP_GUEST_UPDATE_SEARCH).forward(req, resp);
    }

}

