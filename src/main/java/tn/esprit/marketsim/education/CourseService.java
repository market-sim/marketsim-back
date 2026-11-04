package tn.esprit.marketsim.education;

import java.util.List;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import tn.esprit.marketsim.common.NotFoundException;

@Service
@RequiredArgsConstructor
public class CourseService {

    private final CourseRepository repository;

    public List<Course> findAll() {
        return repository.findAll();
    }

    public Course findOne(Long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Cours introuvable"));
    }

    public Course create(Course course) {
        course.setId(null);
        return repository.save(course);
    }

    public Course update(Long id, Course data) {
        Course course = findOne(id);
        course.setTitle(data.getTitle());
        course.setDescription(data.getDescription());
        course.setLevel(data.getLevel());
        course.setLessons(data.getLessons());
        course.setDurationMinutes(data.getDurationMinutes());
        return repository.save(course);
    }

    public void delete(Long id) {
        repository.delete(findOne(id));
    }
}
