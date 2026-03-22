package com.example.ticket.domain.user;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    private User(String email, String encodedPassword) {
        this.email = email;
        this.password = encodedPassword;
        this.createdAt = LocalDateTime.now();
    }

    public static User create(String email, String encodedPassword) {
        return new User(email, encodedPassword);
    }
}
