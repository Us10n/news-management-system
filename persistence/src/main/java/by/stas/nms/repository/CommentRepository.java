package by.stas.nms.repository;

import by.stas.nms.entity.Comment;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface CommentRepository extends MongoRepository<Comment, Long> {
    Optional<Comment> findCommentById(String id);

    Page<Comment> findCommentsByNewsId(PageRequest pageRequest, String id);

    List<Comment> findCommentsByNewsId(String id);

    void deleteCommentById(String id);

    void deleteCommentsByNewsId(String id);

}
