package com.example.silverrock.user;


import com.example.silverrock.global.Response.BaseException;
import com.example.silverrock.global.S3.S3Service;
import com.example.silverrock.global.UtilService;
import com.example.silverrock.login.dto.JwtResponseDTO;
import com.example.silverrock.login.jwt.*;
import com.example.silverrock.user.dto.*;
import com.example.silverrock.user.profile.Profile;
import com.example.silverrock.user.profile.ProfileRepository;
import com.example.silverrock.user.profile.ProfileService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import com.example.silverrock.global.AES128;

import java.util.List;
import java.util.stream.Collectors;

import static com.example.silverrock.global.Response.BaseResponseStatus.*;


@EnableTransactionManagement
@RequiredArgsConstructor
@Service
public class UserService {

    private final UserRepository userRepository;
    private final TokenRepository tokenRepository;
    private final UtilService utilService;
    private final JwtProvider jwtProvider;
    private final JwtService jwtService;
    private final ProfileService profileService;
    private final ProfileRepository profileRepository;
    private final S3Service s3Service;

    /**
     * 유저 생성 후 DB에 저장(회원 가입) with JWT
     */
    @Transactional
    public PostUserRes createUser(PostUserReq postUserReq, MultipartFile multipartFile) throws BaseException {
        if(postUserReq.getPassword().isEmpty()){
            throw new BaseException(PASSWORD_CANNOT_BE_NULL);
        }
        if(!postUserReq.getPassword().equals(postUserReq.getPasswordChk())) {
            throw new BaseException(PASSWORD_MISSMATCH);
        }
        String pwd;
        try{
            pwd = new AES128(Secret.USER_INFO_PASSWORD_KEY).encrypt(postUserReq.getPassword()); // 암호화 코드
        }
        catch (Exception ignored) { // 암호화가 실패하였을 경우 에러 발생
            throw new BaseException(PASSWORD_ENCRYPTION_ERROR);
        }
        User user = new User(postUserReq.getPhoneNum(), postUserReq.getGender(), postUserReq.getNickname(),
                postUserReq.getBirth(), postUserReq.getRegion(), pwd, postUserReq.getIntroduce());
        userRepository.save(user);
        GetS3Res getS3Res;
        if(multipartFile != null) {
            getS3Res = s3Service.uploadSingleFile(multipartFile);
            profileService.saveProfile(getS3Res, user);
        }

        return new PostUserRes(user);
    }


    /**
     * 닉네임 중복 확인
     */
    @Transactional
    public String checkNickname(String nickname) throws BaseException {
        if(userRepository.existsByNickname(nickname)) {
            return "이미 존재하는 닉네임입니다.";
        }
        return "사용 가능한 닉네임입니다";
    }



    /**
     * 유저 로그인 with JWT
    */
    public PostLoginRes login(PostLoginReq postLoginReq) throws BaseException {
        User user = userRepository.findUserByPhoneNum(postLoginReq.getPhoneNum()).get();
        Token existToken = tokenRepository.findTokenByUserId(user.getId()).orElse(null);

        String password;
        try {
            password = new AES128(Secret.USER_INFO_PASSWORD_KEY).decrypt(user.getPassword());
        } catch (Exception ignored) {
            throw new BaseException(PASSWORD_DECRYPTION_ERROR);
        }

        JwtResponseDTO.TokenInfo tokenInfo = jwtProvider.generateToken(user.getId());

        if (postLoginReq.getPassword().equals(password)) {
            if (existToken != null) {
                // Update existing token
                existToken.setAccessToken(tokenInfo.getAccessToken());
                existToken.setRefreshToken(tokenInfo.getRefreshToken());
                Token updateToken = tokenRepository.save(existToken);
                return new PostLoginRes(user, updateToken);
            } else {
                // Save new token
                Token newToken = Token.builder()
                        .accessToken(tokenInfo.getAccessToken())
                        .refreshToken(tokenInfo.getRefreshToken())
                        .user(user)
                        .build();
                tokenRepository.save(newToken);
                return new PostLoginRes(user, newToken);
            }

        } else {
            throw new BaseException(FAILED_TO_LOGIN);
        }
    }

    /**
     *  유저 프로필 변경
     */
    @Transactional
    public String modifyProfile(Long userId, MultipartFile multipartFile) throws BaseException {
        try {
            User user = utilService.findByUserIdWithValidation(userId);
            Profile profile = profileRepository.findProfileById(userId).orElse(null);
            if(profile == null) { // 프로필이 미등록된 사용자가 변경을 요청하는 경우
                GetS3Res getS3Res;
                if(multipartFile != null) {
                    getS3Res = s3Service.uploadSingleFile(multipartFile);
                    profileService.saveProfile(getS3Res, user);
                }
            }
            else { // 프로필이 등록된 사용자가 변경을 요청하는 경우
                // 1. 버킷에서 삭제
                profileService.deleteProfile(profile);
                // 2. Profile Repository에서 삭제
                profileService.deleteProfileById(userId);
                if(multipartFile != null) {
                    GetS3Res getS3Res = s3Service.uploadSingleFile(multipartFile);
                    profileService.saveProfile(getS3Res, user);
                }
            }
            return "프로필 수정이 완료되었습니다.";
        } catch (BaseException exception) {
            throw new BaseException(exception.getStatus());
        }
    }

    /**
     * 유저 로그아웃
    */
    @Transactional
    public String logout(Long userId) throws BaseException {
        try {
            if (userId == 0L) { // 로그아웃 요청은 access token이 만료되더라도 재발급할 필요가 없음.
                User user = tokenRepository.findUserByAccessToken(jwtService.getJwt()).orElse(null);
                if (user != null) {
                    Token token = tokenRepository.findTokenByUserId(user.getId()).orElse(null);
                    tokenRepository.deleteTokenByAccessToken(token.getAccessToken());
                    return "로그아웃 되었습니다.";
                }
                else {  //사용자가 존재하지 않는다면
                    throw new BaseException(INVALID_JWT);
                }
            }
            else { // 토큰이 만료되지 않은 경우
                User logoutUser = utilService.findByUserIdWithValidation(userId);
                //리프레쉬 토큰 삭제
                tokenRepository.deleteTokenByUserId(logoutUser.getId());
                return "로그아웃 되었습니다.";
            }
        } catch (Exception e) {
            throw new BaseException(FAILED_TO_LOGOUT);
        }

    }

    public List<GetNearUserRes> getProfilesByRegion(Long userId) throws BaseException{

        User currentUser = userRepository.findUserById(userId).get();
        String region=currentUser.getRegion();

        List<User> usersInSameRegion = userRepository.findByRegionAndIdNot(region, userId); // 같은 지역이면서 나는 제외해서 조회되도록
        if(usersInSameRegion.isEmpty()){
            throw new BaseException(NONE_NEAR);
        }

        List<GetNearUserRes> getNearUserRes= usersInSameRegion.stream()
                .map(user -> new GetNearUserRes(user.getId(), user.getGender(), user.getNickname(), user.getBirth(), user.getRegion(), user.getIntroduce(),
                        new GetS3Res(user.getProfile().getProfileUrl(), user.getProfile().getProfileFileName()))).collect(Collectors.toList());

        return getNearUserRes;
    }

    //내 정보 조회
    public GetUserInfoRes getUserInfo(Long userId) throws BaseException{

        User currentUser = userRepository.findUserById(userId).get();
        GetUserInfoRes userInfo = new GetUserInfoRes(
                currentUser.getPhoneNum(),
                currentUser.getGender(),
                currentUser.getNickname(),
                currentUser.getBirth(),
                currentUser.getRegion(),
                currentUser.getIntroduce(),
                new GetS3Res(currentUser.getProfile().getProfileUrl(), currentUser.getProfile().getProfileFileName())
        );

        return userInfo;
    }

    //내 정보 수정
    public GetUserInfoRes modifyUserInfo(Long userId, GetUserInfoReq userInfoReq) throws BaseException{

        GetUserInfoRes currentUserInfo = getUserInfo(userId);   //기존 유저 정보
        User currentUser = userRepository.findUserById(userId).get();   //현재 유저

        String phoneNum = currentUserInfo.getPhoneNum();
        String gender = currentUserInfo.getGender();
        String nickname = currentUserInfo.getNickname();
        String birth = currentUserInfo.getBirth();
        String region = currentUserInfo.getRegion();
        String introduce = currentUserInfo.getIntroduce();
        GetS3Res getS3Res = new GetS3Res(currentUserInfo.getGetS3Res().getImgUrl(), currentUserInfo.getGetS3Res().getFileName());

        if(userInfoReq != null){    //null인 경우 메소드 호출시 발생할 수 있는 NullPointerException 방지

            if(userInfoReq.getPhoneNum() != null){
                phoneNum = userInfoReq.getPhoneNum();
                currentUser.setPhoneNum(phoneNum);      //수정 사항 있을시 변경되도록
            }
            if (userInfoReq.getGender() != null) {
                gender = userInfoReq.getGender();
                currentUser.setGender(gender);
            }
            if (userInfoReq.getNickname() != null) {
                nickname = userInfoReq.getNickname();
                currentUser.setNickname(nickname);
            }
            if (userInfoReq.getBirth() != null) {
                birth = userInfoReq.getBirth();
                currentUser.setBirth(birth);
            }
            if (userInfoReq.getRegion() != null) {
                region = userInfoReq.getRegion();
                currentUser.setRegion(region);
            }
            if (userInfoReq.getIntroduce() != null) {
                introduce = userInfoReq.getIntroduce();
                currentUser.setIntroduce(introduce);
            }
        }

        userRepository.save(currentUser);       //변경 사항 db에 업데이트

        GetUserInfoRes newUserInfo = new GetUserInfoRes(
                phoneNum, gender, nickname, birth, region, introduce, getS3Res
        );
        //사진은 수정 요청시엔 받지 않고 기존 유저의 사진 그대로 반환

        return newUserInfo;
    }


}
