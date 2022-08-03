package by.stas.nms.service.impl;

import by.stas.nms.cache.CustomCacheManager;
import by.stas.nms.dto.CommentDto;
import by.stas.nms.dto.NewsDto;
import by.stas.nms.dto.NewsWithCommentsDto;
import by.stas.nms.entity.Comment;
import by.stas.nms.entity.News;
import by.stas.nms.exception.EmptyListRequestedException;
import by.stas.nms.exception.ExceptionHolder;
import by.stas.nms.exception.IncorrectParameterException;
import by.stas.nms.exception.NoSuchElementException;
import by.stas.nms.mapper.CommentMapper;
import by.stas.nms.mapper.NewsMapper;
import by.stas.nms.mapper.NewsWithCommentsMapper;
import by.stas.nms.renovator.Renovator;
import by.stas.nms.repository.CommentRepository;
import by.stas.nms.repository.NewsRepository;
import by.stas.nms.service.NewsService;
import by.stas.nms.validator.NewsWithCommentsDtoValidator;
import by.stas.nms.validator.StringsValidator;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.query.TextCriteria;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static by.stas.nms.exception.ExceptionMessageKey.NEWS_EMPTY_LIST;
import static by.stas.nms.exception.ExceptionMessageKey.NEWS_NOT_FOUND;

@Service
@RequiredArgsConstructor
public class NewsServiceImpl implements NewsService {

    private final static String NEWS_CACHE_NAME = "news";

    private final NewsRepository newsRepository;
    private final CommentRepository commentRepository;
    private final Renovator<NewsWithCommentsDto> newsWithCommentsDtoRenovator;
    private final CustomCacheManager cacheManager;

    @Override
    @Transactional(value = "mongoTransactionManager", propagation = Propagation.REQUIRED)
    public NewsWithCommentsDto create(NewsWithCommentsDto object) {
        //Set create date to news and comments.
        //In order to create new document with auto-generated _id field id field must be null.
        LocalDateTime createDate = LocalDateTime.now();
        object.setDate(createDate);
        object.setId(null);
        object.getComments().forEach(commentDto -> {
            commentDto.setId(null);
            commentDto.setDate(createDate);
        });

        ExceptionHolder exceptionHolder = new ExceptionHolder();
        NewsWithCommentsDtoValidator.isNewsWithCommentsDtoPayloadValid(object, exceptionHolder);
        if (!exceptionHolder.getExceptionMessages().isEmpty()) {
            throw new IncorrectParameterException(exceptionHolder);
        }

        News newsToCreate = NewsWithCommentsMapper.INSTANCE.mapToEntity(object);
        newsRepository.save(newsToCreate);

        List<Comment> comments = object.getComments()
                .stream()
                .map(commentDto -> {
                    commentDto.setNewsId(newsToCreate.getId());
                    return CommentMapper.INSTANCE.mapToEntity(commentDto);
                })
                .toList();
        commentRepository.saveAll(comments);

        NewsWithCommentsDto createdNewsWithCommentDto = NewsWithCommentsMapper.INSTANCE.mapToDto(newsToCreate);
        List<CommentDto> createdCommentDtos = comments
                .stream()
                .map(CommentMapper.INSTANCE::mapToDto).toList();
        createdNewsWithCommentDto.setComments(createdCommentDtos);

        return createdNewsWithCommentDto;
    }

    @Override
    public List<NewsDto> readAll(Integer page, Integer limit) {
        String key = cacheManager.generateKeyForMap(page, limit);

        Object[] cachedNews = cacheManager.getCollectionFromMap(NEWS_CACHE_NAME, key);
        List<NewsDto> newsDtos;
        if (Objects.nonNull(cachedNews)) {
            newsDtos = Arrays.stream(cachedNews)
                    .map(object -> (NewsDto) object)
                    .toList();
        } else {
            PageRequest pageRequest = PageRequest.of(page, limit);
            newsDtos = newsRepository.findAll(pageRequest)
                    .getContent()
                    .stream()
                    .map(NewsMapper.INSTANCE::mapToDto)
                    .toList();

            if (newsDtos.isEmpty()) {
                throw new EmptyListRequestedException(NEWS_EMPTY_LIST);
            }

            cacheManager.putCollectionToMap(NEWS_CACHE_NAME, key, newsDtos.toArray());
        }

        return newsDtos;
    }

    @Override
    public List<NewsDto> readAll(String term, Integer page, Integer limit) {
        ExceptionHolder exceptionHolder = new ExceptionHolder();
        StringsValidator.isTermStringValid(term, exceptionHolder);
        if (!exceptionHolder.getExceptionMessages().isEmpty()) {
            throw new IncorrectParameterException(exceptionHolder);
        }

        String key = cacheManager.generateKeyForMap(term, page, limit);

        Object[] cachedNews = cacheManager.getCollectionFromMap(NEWS_CACHE_NAME, key);
        List<NewsDto> newsDtos;
        if (Objects.nonNull(cachedNews)) {
            newsDtos = Arrays.stream(cachedNews)
                    .map(object -> (NewsDto) object)
                    .toList();
        } else {
            PageRequest pageRequest = PageRequest.of(page, limit, Sort.by(Sort.Direction.DESC, "score"));
            TextCriteria criteria = TextCriteria.forDefaultLanguage().matching(term);
            newsDtos = newsRepository.findAllBy(criteria, pageRequest)
                    .stream()
                    .map(NewsMapper.INSTANCE::mapToDto)
                    .toList();

            if (newsDtos.isEmpty()) {
                throw new EmptyListRequestedException(NEWS_EMPTY_LIST);
            }

            cacheManager.putCollectionToMap(NEWS_CACHE_NAME, key, newsDtos.toArray());
        }

        return newsDtos;
    }

    @Override
    public NewsWithCommentsDto readById(String id, Integer page, Integer limit) {
        ExceptionHolder exceptionHolder = new ExceptionHolder();
        StringsValidator.isIdStringValid(id, exceptionHolder);
        if (!exceptionHolder.getExceptionMessages().isEmpty()) {
            throw new IncorrectParameterException(exceptionHolder);
        }

        String key = cacheManager.generateKeyForMap(id, page, limit);

        Optional<Object> cachedNews = cacheManager.getSingleObjectFromMap(NEWS_CACHE_NAME, key);
        NewsWithCommentsDto newsWithCommentsDto;
        if (cachedNews.isPresent()) {
            newsWithCommentsDto = (NewsWithCommentsDto) cachedNews.get();
        } else {
            Optional<News> optionalNews = newsRepository.findNewsById(id);
            News foundNews = optionalNews.orElseThrow(
                    () -> new NoSuchElementException(NEWS_NOT_FOUND)
            );

            PageRequest pageRequest = PageRequest.of(page, limit);
            List<Comment> foundComments = commentRepository.findCommentsByNewsId(pageRequest, id).getContent();

            newsWithCommentsDto = NewsWithCommentsMapper.INSTANCE.mapToDto(foundNews);
            List<CommentDto> commentDtos = foundComments.stream().map(CommentMapper.INSTANCE::mapToDto).toList();
            newsWithCommentsDto.setComments(commentDtos);

            cacheManager.putSingleObjectToMap(NEWS_CACHE_NAME, key, newsWithCommentsDto);
        }

        return newsWithCommentsDto;
    }

    @Override
    public NewsWithCommentsDto readById(String id) {
        return readById(id, 0, 10);
    }

    @Override
    @Transactional
    public NewsWithCommentsDto update(NewsWithCommentsDto object) {
        NewsWithCommentsDto existingNewsWithCommentsDto = readById(object.getId());
        newsWithCommentsDtoRenovator.updateObject(object, existingNewsWithCommentsDto);

        ExceptionHolder exceptionHolder = new ExceptionHolder();
        NewsWithCommentsDtoValidator.isNewsWithCommentsDtoValid(object, exceptionHolder);
        if (!exceptionHolder.getExceptionMessages().isEmpty()) {
            throw new IncorrectParameterException(exceptionHolder);
        }

        News newNews = NewsWithCommentsMapper.INSTANCE.mapToEntity(object);
        newsRepository.save(newNews);

        return NewsWithCommentsMapper.INSTANCE.mapToDto(newNews);
    }

    @Override
    @Transactional
    public void delete(String id) {
        //Check whether news with this id exist or not
        readById(id);

        commentRepository.deleteCommentsByNewsId(id);
        newsRepository.deleteById(id);
    }
}
