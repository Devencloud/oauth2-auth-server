package com.dev.auth_server.repository;

import org.springframework.data.jpa.repository.JpaRepository;

import com.dev.auth_server.model.User;

import java.util.Optional;


public interface UserRepository extends JpaRepository<User,Long > {
    Optional<User>  findByEmail(String email);

    

    
}
