package app.popcornsociety.account.internal.persistence;

import app.popcornsociety.shared.persistence.DateAudit;
import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.Collection;

@Entity
@org.hibernate.annotations.DynamicUpdate
@Table(
    uniqueConstraints = {
      @UniqueConstraint(columnNames = {"username"}),
      @UniqueConstraint(columnNames = {"email"})
    })
public class Account extends DateAudit {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @NotNull private String username;
  @NotNull private String email;
  @JsonIgnore private String password;
  private String firstName;
  private String lastName;
  private String phone;
  private String bio;

  private LocalDate birthday;

  @Column(length = 255)
  private String imageUrlToken;

  @JsonIgnore
  @Column(nullable = false, insertable = false, updatable = false)
  private long securityVersion;

  public long getSecurityVersion() {
    return securityVersion;
  }

  @JsonIgnore private Boolean locked = false;
  @JsonIgnore private Boolean enabled = false;

  @ManyToMany(fetch = FetchType.LAZY)
  private Collection<Role> roles;

  public Account() {}

  public Account(String username, String email, String password) {
    this.username = username;
    this.email = email;
    this.password = password;
  }

  public Account(String username, String email) {
    this.username = username;
    this.email = email;
  }

  public Long getId() {
    return id;
  }

  public void setId(Long id) {
    this.id = id;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(String username) {
    this.username = username;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(String email) {
    this.email = email;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(String password) {
    this.password = password;
  }

  public String getFirstName() {
    return firstName;
  }

  public void setFirstName(String firstName) {
    this.firstName = firstName;
  }

  public String getLastName() {
    return lastName;
  }

  public void setLastName(String lastName) {
    this.lastName = lastName;
  }

  public String getPhone() {
    return phone;
  }

  public void setPhone(String phone) {
    this.phone = phone;
  }

  public String getBio() {
    return bio;
  }

  public void setBio(String bio) {
    this.bio = bio;
  }

  public LocalDate getBirthday() {
    return birthday;
  }

  public void setBirthday(LocalDate birthday) {
    this.birthday = birthday;
  }

  public String getImageUrlToken() {
    return imageUrlToken;
  }

  public void setImageUrlToken(String imageUrlToken) {
    this.imageUrlToken = imageUrlToken;
  }

  public Boolean getLocked() {
    return locked;
  }

  public void setLocked(Boolean locked) {
    this.locked = locked;
  }

  public Boolean getEnabled() {
    return enabled;
  }

  public void setEnabled(Boolean enabled) {
    this.enabled = enabled;
  }

  public Collection<Role> getRoles() {
    return roles;
  }

  public void setRoles(Collection<Role> roles) {
    this.roles = roles;
  }
}
