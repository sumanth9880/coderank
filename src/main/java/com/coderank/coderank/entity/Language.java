package com.coderank.coderank.entity;


import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "languages")
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor @Builder
public class Language {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String version;

    @Column(name = "docker_image", nullable = false)
    private String dockerImage;

    @Column(name = "source_file", nullable = false)
    private String sourceFile;

    @Column(name = "run_command", nullable = false)
    private String runCommand;
}
