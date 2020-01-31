package play.data.validation;

import net.sf.oval.ConstraintViolation;
import net.sf.oval.context.MethodParameterContext;
import net.sf.oval.guard.Guard;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import play.PlayPlugin;
import play.exceptions.UnexpectedException;
import play.mvc.ActionInvoker;
import play.mvc.Http;
import play.mvc.Http.Cookie;
import play.mvc.Http.Request;
import play.mvc.Http.Response;
import play.mvc.Scope;
import play.mvc.Scope.RenderArgs;
import play.mvc.Scope.Session;
import play.mvc.results.Result;
import play.utils.ErrorsCookieCrypter;
import play.utils.Java;

import javax.annotation.Nonnull;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ValidationPlugin extends PlayPlugin {

    public static final ThreadLocal<Map<Object, String>> keys = new ThreadLocal<>();
    private static final ErrorsCookieCrypter errorsCookieCrypter = new ErrorsCookieCrypter();
    private static final Logger securityLogger = LoggerFactory.getLogger("security");

    @Override
    public void beforeInvocation() {
        keys.set(new HashMap<>());
        Validation.current.set(new Validation());
    }

    @Override
    public void beforeActionInvocation(Request request, Response response, Session session, RenderArgs renderArgs,
                                       Scope.Flash flash, Method actionMethod) {
        Validation.current.set(restore(request));
        boolean verify = false;
        for (Annotation[] annotations : actionMethod.getParameterAnnotations()) {
            if (annotations.length > 0) {
                verify = true;
                break;
            }
        }
        if (!verify) {
            return;
        }
        List<ConstraintViolation> violations = new Validator().validateAction(request, session, actionMethod);
        ArrayList<Error> errors = new ArrayList<>();
        String[] paramNames = Java.parameterNames(actionMethod);
        for (ConstraintViolation violation : violations) {
            String key = paramNames[((MethodParameterContext) violation.getContext()).getParameterIndex()];
            String[] variables = violation.getMessageVariables() == null ? new String[0]
              : violation.getMessageVariables().values().toArray(new String[0]);
            errors.add(new Error(
              key, violation.getMessage(), variables, violation.getSeverity())
            );
        }
        Validation.current.get().errors.addAll(errors);
    }

    @Override
    public void onActionInvocationResult(@Nonnull Request request, @Nonnull Response response, @Nonnull Session session, @Nonnull RenderArgs renderArgs, Result result) {
        save(request, response);
    }

    @Override
    public void onActionInvocationException(@Nonnull Http.Request request, @Nonnull Response response, @Nonnull Throwable e) {
        clear(response);
    }

    @Override
    public void onActionInvocationFinally(@Nonnull Request request) {
        onJobInvocationFinally();
    }

    @Override
    public void onJobInvocationFinally() {
        if (keys.get() != null) {
            keys.get().clear();
        }
        keys.remove();
        Validation.current.remove();
    }

    // ~~~~~~
    static class Validator extends Guard {
        public List<ConstraintViolation> validateAction(Http.Request request, Session session, Method actionMethod) {
            List<ConstraintViolation> violations = new ArrayList<>();
            Object[] rArgs = ActionInvoker.getActionMethodArgs(request, session, actionMethod);
            validateMethodParameters(null, actionMethod, rArgs, violations);
            validateMethodPre(null, actionMethod, rArgs, violations);
            return violations;
        }
    }

    private static final Pattern errorsParser = Pattern.compile("\u0000([^:]*):([^\u0000]*)\u0000");

    static Validation restore(Request request) {
        try {
            Validation validation = new Validation();
            Http.Cookie cookie = request.cookies.get(Scope.COOKIE_PREFIX + "_ERRORS");
            if (cookie != null) {
                String errorsData = errorsCookieCrypter.decrypt(URLDecoder.decode(cookie.value, "utf-8"));
                if (errorsData != null) {
                    Matcher matcher = errorsParser.matcher(errorsData);
                    while (matcher.find()) {
                        String[] g2 = matcher.group(2).split("\u0001", -1);
                        String message = g2[0];
                        String[] args = new String[g2.length - 1];
                        System.arraycopy(g2, 1, args, 0, args.length);
                        validation.errors.add(new Error(matcher.group(1), message, args));
                    }
                } else {
                    securityLogger.error("Failed decrypt cookie {} : {} ", Scope.COOKIE_PREFIX + "_ERRORS", cookie.value);
                }
            }
            return validation;
        } catch (Exception e) {
            return new Validation();
        }
    }

    static void save(Request request, Response response) {
        if (response == null) {
            // Some request like WebSocket don't have any response
            return;
        }
        if (Validation.errors().isEmpty()) {
            // Only send "delete cookie" header when the cookie was present in the request
            if (request.cookies.containsKey(Scope.COOKIE_PREFIX + "_ERRORS")) {
                response.setCookie(Scope.COOKIE_PREFIX + "_ERRORS", "", null, "/", 0, Scope.COOKIE_SECURE, Scope.SESSION_HTTPONLY);
            }
            return;
        }
        try {
            StringBuilder errors = new StringBuilder();
            if (Validation.current() != null && Validation.current().keep) {
                for (Error error : Validation.errors()) {
                    errors.append("\u0000");
                    errors.append(error.key);
                    errors.append(":");
                    errors.append(error.message);
                    for (String variable : error.variables) {
                        errors.append("\u0001");
                        errors.append(variable);
                    }
                    errors.append("\u0000");
                }
            }
            String errorsData = URLEncoder.encode(errorsCookieCrypter.encrypt(errors.toString()), "utf-8");
            response.setCookie(Scope.COOKIE_PREFIX + "_ERRORS", errorsData, null, "/", null, Scope.COOKIE_SECURE, Scope.SESSION_HTTPONLY);
        } catch (Exception e) {
            throw new UnexpectedException("Errors serializationProblem", e);
        }
    }

    private void clear(@Nonnull Response response) {
        try {
            if (response.cookies != null) {
                Cookie cookie = new Cookie(Scope.COOKIE_PREFIX + "_ERRORS", "");
                cookie.sendOnError = true;
                response.cookies.put(cookie.name, cookie);
            }
        } catch (Exception e) {
            throw new UnexpectedException("Errors serializationProblem", e);
        }
    }
}
