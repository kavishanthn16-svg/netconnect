package com.netconnect.common.contracts;

public interface AuthService {
    AuthResult authenticate(String displayName, String accessCode);
}
