package com.marketai.auth.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Entity
@Table(name = "users")
// Lombok's @Data generates equals, hashCode and toString over *every* field, including JPA
// relations. On an entity with a bidirectional mapping that recurses forever:
// Holding.hashCode() reads its portfolio, Portfolio.hashCode() reads its holdings list, which
// reads this holding again — a StackOverflowError, reproduced and confirmed before this fix.
// The same recursion applies to toString(), so simply logging an entity crashed the thread.
//
// On lazy relations it is also a LazyInitializationException (or a silent N+1) as soon as
// equals/hashCode is called outside a session.
//
// Identity is therefore the primary key alone, which is what JPA semantics actually mean by
// "the same row", and relations are excluded from toString.
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class User implements UserDetails {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)

    @EqualsAndHashCode.Include
    @ToString.Include    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, unique = true, length = 150)
    private String email;

    // Never serialized — several entities carry a `User user` FK (CashAccount, LedgerTransfer,
    // RecurringInvestment, ...) and some controllers return those entities directly rather than
    // a DTO. Without @JsonIgnore here, any such response leaks the bcrypt hash to the client
    // (confirmed live: POST /api/ledger/accounts' response body included this field verbatim).
    @JsonIgnore
    @Column(nullable = false)
    private String password;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    @Builder.Default
    private Set<Role> roles = new HashSet<>();

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(nullable = false)
    @Builder.Default
    private boolean emailVerified = false;

    @Column(nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    @Override
    public Collection<? extends GrantedAuthority> getAuthorities() {
        return roles.stream()
                .map(r -> new SimpleGrantedAuthority(r.getName().name()))
                .collect(Collectors.toSet());
    }

    @Override
    public String getUsername() {
        return email;
    }

    @Override
    public boolean isAccountNonExpired() { return true; }

    @Override
    public boolean isAccountNonLocked() { return true; }

    @Override
    public boolean isCredentialsNonExpired() { return true; }
}
